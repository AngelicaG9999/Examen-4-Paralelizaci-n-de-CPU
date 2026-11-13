package servicios;

import java.lang.management.ManagementFactory;
import java.nio.Buffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.SourceDataLine;
import com.sun.management.OperatingSystemMXBean;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

public class ServicioDecodificador {
    public static final int TAMAÑO_MAXIMO_COLA = 30;
    private final OperatingSystemMXBean beanSO =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private volatile boolean ejecutando;
    private volatile boolean videoTerminado = true;
    private volatile boolean audioTerminado = true;
    private volatile boolean procesamientoAudioTerminado = true;
    private volatile boolean salidaAudioTerminada = true;
    private volatile boolean tieneAudio;
    private volatile Long primerTimestampVideo;

    public record ContenedorFrame(Frame frame, long tiempo, long timestampUs) {}
    public record ContenedorAudio(Buffer[] samples, int numChannels, int sampleRate, long timestampUs) {}
    public record ContenedorAudioProcesado(byte[] bytesPCM, int numChannels, int sampleRate, long timestampUs) {}
    public record Metricas(long memoriaHeapMB, long memoriaOffHeapMB,
                           double porcentajeUsoCPU, int ocupacionCola, long tiempoLectura) {}

    private final BlockingQueue<ContenedorFrame> colaVideo = new ArrayBlockingQueue<>(TAMAÑO_MAXIMO_COLA);
    private final BlockingQueue<ContenedorAudio> colaAudio = new ArrayBlockingQueue<>(TAMAÑO_MAXIMO_COLA);
    private final BlockingQueue<ContenedorAudioProcesado> colaAudioProcesado =
            new ArrayBlockingQueue<>(TAMAÑO_MAXIMO_COLA);

    // El tiempo del video sigue al audio. Sin audio se usa el reloj del sistema.
    private SourceDataLine relojAudio;
    private long origenUs;
    private long origenNanos;
    private boolean relojIniciado;

    public synchronized void prepararReproduccion() {
        if (ejecutando) throw new IllegalStateException("Ya hay una reproduccion activa");
        limpiarColas();
        primerTimestampVideo = null;
        tieneAudio = false;
        videoTerminado = false;
        audioTerminado = false;
        procesamientoAudioTerminado = false;
        salidaAudioTerminada = false;
        relojAudio = null;
        relojIniciado = false;
        ejecutando = true;
    }

    public boolean isEjecutando() { return ejecutando; }
    public boolean tieneAudio() { return tieneAudio; }
    public boolean isSalidaAudioTerminada() { return salidaAudioTerminada; }

    public boolean hayVideoPendiente() {
        return ejecutando && (!videoTerminado || !colaVideo.isEmpty());
    }

    public boolean hayAudioPorProcesar() {
        return ejecutando && (!audioTerminado || !colaAudio.isEmpty());
    }

    public boolean hayAudioProcesadoPendiente() {
        return ejecutando && (!procesamientoAudioTerminado || !colaAudioProcesado.isEmpty());
    }

    public void finalizarProcesamientoAudio() { procesamientoAudioTerminado = true; }

    public void encolarAudioProcesado(ContenedorAudioProcesado audio) throws InterruptedException {
        colaAudioProcesado.put(audio);
    }

    public ContenedorAudioProcesado getSiguienteAudioProcesado() throws InterruptedException {
        return colaAudioProcesado.poll(50, TimeUnit.MILLISECONDS);
    }

    public ContenedorAudio getSiguienteAudio() throws InterruptedException {
        return colaAudio.poll(50, TimeUnit.MILLISECONDS);
    }

    public ContenedorFrame getSiguienteFrame() throws InterruptedException {
        return colaVideo.poll(50, TimeUnit.MILLISECONDS);
    }

    // Las pistas se leen por separado para que una cola llena no frene a la otra.
    public void iniciarDecodificacion(String ruta) {
        try (var grabber = new FFmpegFrameGrabber(ruta)) {
            grabber.start();
            tieneAudio = grabber.hasAudio();
            if (!grabber.hasVideo()) return;
            Frame frame;
            while (ejecutando && (frame = grabber.grabImage()) != null) {
                if (primerTimestampVideo == null) primerTimestampVideo = frame.timestamp;
                long inicio = System.nanoTime();
                Frame copia = frame.clone();
                boolean encolado = false;
                try {
                    colaVideo.put(new ContenedorFrame(
                            copia, (System.nanoTime() - inicio) / 1_000_000, frame.timestamp));
                    encolado = true;
                } finally {
                    if (!encolado) copia.close();
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            detener();
        } catch (Exception ex) {
            System.err.println("[Video] " + ex.getMessage());
            detener();
        } finally {
            videoTerminado = true;
        }
    }

    public void iniciarDecodificacionAudio(String ruta) {
        try (var grabber = new FFmpegFrameGrabber(ruta)) {
            grabber.start();
            if (!grabber.hasAudio()) return;
            Frame frame;
            while (ejecutando && (frame = grabber.grabSamples()) != null) {
                Buffer[] copias = new Buffer[frame.samples.length];
                for (int i = 0; i < copias.length; i++) {
                    copias[i] = Util.duplicarBuffer(frame.samples[i]);
                }
                colaAudio.put(new ContenedorAudio(
                        copias, frame.audioChannels, frame.sampleRate, frame.timestamp));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            detener();
        } catch (Exception ex) {
            System.err.println("[Audio] " + ex.getMessage());
            detener();
        } finally {
            audioTerminado = true;
        }
    }

    public Long esperarPrimerTimestampVideo() throws InterruptedException {
        while (ejecutando && primerTimestampVideo == null && !videoTerminado) Thread.sleep(2);
        return primerTimestampVideo;
    }

    public synchronized boolean isRelojIniciado() { return relojIniciado; }

    public synchronized void iniciarReloj(long timestampUs) {
        if (relojIniciado) return;
        origenUs = timestampUs;
        origenNanos = System.nanoTime();
        relojIniciado = true;
    }

    public synchronized void usarRelojAudio(SourceDataLine linea, long timestampUs) {
        relojAudio = linea;
        origenUs = timestampUs;
        relojIniciado = true;
    }

    public synchronized long getTiempoActualUs() {
        if (relojAudio != null) return origenUs + relojAudio.getMicrosecondPosition();
        return origenUs + (System.nanoTime() - origenNanos) / 1000;
    }

    public synchronized void finalizarSalidaAudio() {
        if (relojAudio != null) {
            origenUs = getTiempoActualUs();
            origenNanos = System.nanoTime();
            relojAudio = null;
        }
        salidaAudioTerminada = true;
    }

    public void detener() {
        ejecutando = false;
        limpiarColas();
    }

    // Se llama despues de que el controlador haya esperado a todos los hilos.
    public void finalizarReproduccion() { detener(); }

    private void limpiarColas() {
        ContenedorFrame pendiente;
        while ((pendiente = colaVideo.poll()) != null) pendiente.frame().close();
        colaAudio.clear();
        colaAudioProcesado.clear();
    }

    public Metricas getMetricas(long tiempoLectura) {
        Runtime runtime = Runtime.getRuntime();
        long mb = 1048576;
        long heap = (runtime.totalMemory() - runtime.freeMemory()) / mb;
        long memoriaProceso = beanSO.getCommittedVirtualMemorySize() / mb;
        return new Metricas(heap, Math.max(0, memoriaProceso - heap),
                beanSO.getCpuLoad() * 100, colaVideo.size(), tiempoLectura);
    }
}
