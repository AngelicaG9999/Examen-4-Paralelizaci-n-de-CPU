package controladores;

import org.bytedeco.javacv.Java2DFrameConverter;
import servicios.ServicioDecodificador;
import servicios.Util;
import vistas.VistaReproductor;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.swing.SwingUtilities;
import java.io.File;

public class ControladorReproductor {
    private final VistaReproductor vista;
    private final ServicioDecodificador servicio;
    private volatile long ultimoTiempo;
    private volatile Thread[] trabajadores = new Thread[0];
    private volatile Thread supervisor;
    private volatile SourceDataLine lineaActual;
    private volatile boolean cerrando;

    public ControladorReproductor(VistaReproductor vista, ServicioDecodificador servicio) {
        this.vista = vista;
        this.servicio = servicio;
        vista.setSelecionarClick(evento -> iniciarProceso());
        vista.setCerrar(this::cerrar);
    }

    private void iniciarProceso() {
        if (sesionActiva() || cerrando) return;
        File archivo = vista.solicitarArchivoVideo();
        if (archivo != null) iniciarReproduccion(archivo);
    }

    private boolean sesionActiva() {
        Thread actual = supervisor;
        return servicio.isEjecutando() || (actual != null && actual.isAlive());
    }

    public synchronized void iniciarReproduccion(File archivo) {
        if (sesionActiva() || cerrando) return;
        servicio.prepararReproduccion();
        ultimoTiempo = 0;
        vista.setReproduciendo(true);
        trabajadores = new Thread[] {
            new Thread(() -> servicio.iniciarDecodificacion(archivo.getAbsolutePath()), "Hilo Decodificacion"),
            new Thread(() -> servicio.iniciarDecodificacionAudio(archivo.getAbsolutePath()), "Hilo Decodificacion Audio"),
            new Thread(this::cicloProcesamientoAudio, "Hilo Procesamiento Audio"),
            new Thread(this::cicloAudio, "Hilo Audio"),
            new Thread(this::cicloRenderizado, "Hilo Renderizado")
        };
        Thread telemetria = new Thread(this::cicloTelemetria, "Hilo Telemetria");
        supervisor = new Thread(() -> {
            for (Thread hilo : trabajadores) esperarHilo(hilo);
            servicio.finalizarReproduccion();
            telemetria.interrupt();
            esperarHilo(telemetria);
            vista.setReproduciendo(false);
        }, "Hilo Supervisor");
        for (Thread hilo : trabajadores) hilo.start();
        telemetria.start();
        supervisor.start();
    }

    // join espera el cierre del hilo; si se interrumpe, primero cancelamos la sesion.
    private void esperarHilo(Thread hilo) {
        boolean interrumpido = false;
        while (hilo.isAlive()) {
            try { hilo.join(); }
            catch (InterruptedException ex) {
                interrumpido = true;
                detenerReproduccion();
            }
        }
        if (interrumpido) Thread.currentThread().interrupt();
    }

    private void cicloProcesamientoAudio() {
        Util.ProcesadorEco eco = new Util.ProcesadorEco();
        ServicioDecodificador.ContenedorAudio ultimo = null;
        long finAudioUs = 0;
        try {
            while (servicio.hayAudioPorProcesar()) {
                var audio = servicio.getSiguienteAudio();
                if (audio == null) continue;
                byte[] pcm = eco.procesar(audio.samples(), audio.numChannels(), audio.sampleRate());
                servicio.encolarAudioProcesado(new ServicioDecodificador.ContenedorAudioProcesado(
                        pcm, audio.numChannels(), audio.sampleRate(), audio.timestampUs()));
                ultimo = audio;
                finAudioUs = audio.timestampUs() + (pcm.length / (audio.numChannels() * 2))
                        * 1_000_000L / audio.sampleRate();
            }
            if (servicio.isEjecutando() && ultimo != null) {
                servicio.encolarAudioProcesado(new ServicioDecodificador.ContenedorAudioProcesado(
                        eco.finalizar(), ultimo.numChannels(), ultimo.sampleRate(), finAudioUs));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            detenerReproduccion();
        } catch (Exception ex) {
            fallar("procesamiento de audio", ex);
        } finally {
            servicio.finalizarProcesamientoAudio();
        }
    }

    protected SourceDataLine crearLineaAudio(AudioFormat formato) throws LineUnavailableException {
        return AudioSystem.getSourceDataLine(formato);
    }

    private void cicloAudio() {
        SourceDataLine linea = null;
        boolean iniciada = false;
        long primerTimestampUs = 0;
        int precargaBytes = 0;
        int cargados = 0;
        try {
            while (servicio.hayAudioProcesadoPendiente()) {
                var audio = servicio.getSiguienteAudioProcesado();
                if (audio == null || audio.bytesPCM().length == 0) continue;
                if (linea == null) {
                    AudioFormat formato = new AudioFormat(audio.sampleRate(), 16, audio.numChannels(), true, false);
                    linea = crearLineaAudio(formato);
                    lineaActual = linea;
                    int bytesPorSegundo = audio.sampleRate() * formato.getFrameSize();
                    linea.open(formato, bytesPorSegundo / 5); // Buffer de unos 200 ms.
                    precargaBytes = Math.min(linea.getBufferSize() / formato.getFrameSize(),
                            audio.sampleRate() / 10) * formato.getFrameSize();
                    primerTimestampUs = audio.timestampUs();
                } else if (linea.getFormat().getChannels() != audio.numChannels()
                        || linea.getFormat().getSampleRate() != audio.sampleRate()) {
                    throw new IllegalArgumentException("Cambio de formato de audio no soportado");
                }
                byte[] pcm = audio.bytesPCM();
                int offset = 0;
                if (!iniciada) {
                    // Primero llenamos 100 ms; esta cantidad siempre cabe sin arrancar.
                    offset = Math.min(pcm.length, precargaBytes - cargados);
                    escribirAudio(linea, pcm, 0, offset);
                    cargados += offset;
                    if (cargados == precargaBytes) {
                        iniciarSalidaAudio(linea, primerTimestampUs);
                        iniciada = true;
                    }
                }
                if (iniciada) escribirAudio(linea, pcm, offset, pcm.length - offset);
            }
            if (linea != null && servicio.isEjecutando()) {
                if (!iniciada) iniciarSalidaAudio(linea, primerTimestampUs);
                linea.drain(); // Incluye archivos mas cortos que la precarga y la cola del eco.
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            detenerReproduccion();
        } catch (Exception ex) {
            fallar("salida de audio", ex);
        } finally {
            servicio.finalizarSalidaAudio();
            if (linea != null) {
                linea.stop();
                linea.flush();
                linea.close();
            }
            lineaActual = null;
        }
    }

    private void escribirAudio(SourceDataLine linea, byte[] pcm, int offset, int cantidad) {
        while (cantidad > 0 && servicio.isEjecutando()) {
            int escritos = linea.write(pcm, offset, cantidad);
            if (escritos <= 0) {
                if (!servicio.isEjecutando()) return;
                throw new IllegalStateException("El dispositivo no acepta audio");
            }
            offset += escritos;
            cantidad -= escritos;
        }
    }

    private void iniciarSalidaAudio(SourceDataLine linea, long timestampUs) throws InterruptedException {
        // No saltar el video inicial cuando la pista de audio comienza tarde.
        Long primerVideo = servicio.esperarPrimerTimestampVideo();
        if (primerVideo != null && timestampUs > primerVideo)
            servicio.iniciarReloj(primerVideo);
        if (servicio.isRelojIniciado()) esperarPresentacion(timestampUs);
        if (!servicio.isEjecutando()) return;
        linea.start();
        servicio.usarRelojAudio(linea, timestampUs);
    }

    private void cicloRenderizado() {
        try (var conversor = new Java2DFrameConverter()) {
            while (servicio.hayVideoPendiente()) {
                var contenedor = servicio.getSiguienteFrame();
                if (contenedor == null) continue;
                try (var frame = contenedor.frame()) {
                    while (servicio.isEjecutando() && !servicio.isRelojIniciado()) {
                        if (!servicio.tieneAudio() || servicio.isSalidaAudioTerminada()) {
                            servicio.iniciarReloj(contenedor.timestampUs());
                        } else Thread.sleep(2);
                    }
                    if (!servicio.isEjecutando()) break;
                    esperarPresentacion(contenedor.timestampUs());
                    if (!servicio.isEjecutando()) break;
                    ultimoTiempo = contenedor.tiempo();
                    // Si hubo un retraso, recuperar la sincronizacion sin retrasar el audio.
                    if (servicio.getTiempoActualUs() - contenedor.timestampUs() > 100_000) continue;
                    var imagen = Java2DFrameConverter.cloneBufferedImage(conversor.convert(frame));
                    vista.actualizarImagenVideo(imagen);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            detenerReproduccion();
        } catch (Exception ex) {
            fallar("renderizado", ex);
        }
    }

    private void esperarPresentacion(long timestampUs) throws InterruptedException {
        while (servicio.isEjecutando()) {
            long restanteUs = timestampUs - servicio.getTiempoActualUs();
            if (restanteUs <= 0) return;
            Thread.sleep(Math.min(10, Math.max(1, restanteUs / 1000)));
        }
    }

    private void cicloTelemetria() {
        try {
            while (servicio.isEjecutando()) {
                vista.actualizarTelemetria(servicio.getMetricas(ultimoTiempo));
                Thread.sleep(200);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void fallar(String etapa, Exception ex) {
        if (servicio.isEjecutando()) System.err.println("[Controlador] Error en " + etapa + ": " + ex.getMessage());
        detenerReproduccion();
    }

    public void detenerReproduccion() {
        servicio.detener();
        for (Thread hilo : trabajadores) if (hilo != Thread.currentThread()) hilo.interrupt();
        SourceDataLine linea = lineaActual;
        if (linea != null) {
            linea.stop();
            linea.flush();
            linea.close(); // Desbloquea write/drain en caso de cancelacion.
        }
    }

    private synchronized void cerrar() {
        if (cerrando) return;
        cerrando = true;
        detenerReproduccion();
        Thread ultimoSupervisor = supervisor;
        new Thread(() -> {
            if (ultimoSupervisor != null) esperarHilo(ultimoSupervisor);
            SwingUtilities.invokeLater(vista::dispose);
        }, "Hilo Cierre").start();
    }
}
