import controladores.ControladorReproductor;
import servicios.*;
import vistas.VistaReproductor;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import javax.sound.sampled.*;
import javax.swing.SwingUtilities;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.*;
import java.util.*;

// Sin JUnit ni dependencias adicionales. Las integraciones usan FFmpeg real y
// un dispositivo simulado: no abren ventanas visibles ni emiten sonido.
public class PruebasReproductor {
    private static int pruebas;
    private static void comprobar(boolean condicion, String mensaje) {
        if (!condicion) throw new AssertionError(mensaje);
    }
    private static void pasa(String nombre) { pruebas++; System.out.println("OK " + nombre); }
    private static int muestra(byte[] pcm, int indice) {
        return (short) ((pcm[indice * 2] & 255) | (pcm[indice * 2 + 1] << 8));
    }

    private static void probarCopiasYPCM() {
        FloatBuffer original = FloatBuffer.wrap(new float[] {9, .25f, -.5f, 8});
        original.position(1).limit(3).mark();
        FloatBuffer copia = (FloatBuffer) Util.duplicarBuffer(original);
        copia.put(0, 1);
        original.reset();
        comprobar(original.position() == 1 && original.get(1) == .25f, "Copia float compartida o cursor modificado");
        Buffer[] tipos = {ShortBuffer.wrap(new short[]{1}), ByteBuffer.wrap(new byte[]{1}),
                IntBuffer.wrap(new int[]{1}), DoubleBuffer.wrap(new double[]{1}),
                LongBuffer.wrap(new long[]{1}), CharBuffer.wrap(new char[]{1})};
        for (Buffer tipo : tipos) comprobar(Util.duplicarBuffer(tipo) != tipo, "Copia no independiente");
        byte[] pcm = Util.convertirMuestrasABytesPCM(new Buffer[]{
                ShortBuffer.wrap(new short[]{1000, 2000}), ShortBuffer.wrap(new short[]{-3000, -4000})}, 2);
        comprobar(Arrays.equals(pcm, new byte[]{-24,3,72,-12,-48,7,96,-16}), "Canales short planares mal entrelazados");
        pcm = Util.convertirMuestrasABytesPCM(new Buffer[]{
                FloatBuffer.wrap(new float[]{1, Float.NaN}), FloatBuffer.wrap(new float[]{-1, .5f})}, 2);
        comprobar(muestra(pcm,0)==32767 && muestra(pcm,1)==-32768 && muestra(pcm,2)==0
                && muestra(pcm,3)==16384, "Conversion float/clipping/NaN incorrecta");
        pcm = Util.convertirMuestrasABytesPCM(new Buffer[]{ByteBuffer.wrap(new byte[]{0,(byte)128,(byte)255})},1);
        comprobar(muestra(pcm,0)==-32768 && muestra(pcm,1)==0 && muestra(pcm,2)==32512, "PCM U8 incorrecto");
        pcm = Util.convertirMuestrasABytesPCM(new Buffer[]{IntBuffer.wrap(new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE})},1);
        comprobar(muestra(pcm,0)==-32768 && muestra(pcm,1)==32767, "PCM S32 incorrecto");
        pasa("copias independientes y PCM planar/empaquetado");
    }

    private static void probarEco() {
        short[] entrada = new short[300]; entrada[0]=20000; entrada[3]=-16000;
        Util.ProcesadorEco eco = new Util.ProcesadorEco();
        byte[] salida = new byte[600];
        int offset=0;
        for (int frames : new int[]{7,1,29,13,100}) {
            short[] bloque=Arrays.copyOfRange(entrada,offset*2,(offset+frames)*2);
            byte[] procesado=eco.procesar(new Buffer[]{ShortBuffer.wrap(bloque)},2,1000);
            System.arraycopy(procesado,0,salida,offset*4,procesado.length); offset+=frames;
        }
        for (int i=0;i<300;i++) {
            int esperado = (i==0 || i==200) ? 10000 : (i==3 || i==203) ? -8000 : 0;
            comprobar(muestra(salida,i)==esperado,"Eco mezclo canales o perdio continuidad en muestra " + i);
        }
        eco = new Util.ProcesadorEco();
        eco.procesar(new Buffer[]{ShortBuffer.wrap(new short[]{20000,-16000})},2,48000);
        byte[] cola=eco.finalizar();
        comprobar(cola.length==4800*4 && muestra(cola,9598)==10000 && muestra(cola,9599)==-8000,
                "Se perdio la ultima repeticion del eco");
        byte[] limpio=new Util.ProcesadorEco().procesar(new Buffer[]{ShortBuffer.allocate(10000)},2,48000);
        comprobar(Arrays.equals(limpio,new byte[limpio.length]),"Historial filtrado a otra reproduccion");
        pasa("eco estereo, limites de bloque, cola final e historial por sesion");
        Buffer[] bloque={FloatBuffer.allocate(1024),FloatBuffer.allocate(1024)};
        eco=new Util.ProcesadorEco();
        for(int i=0;i<100;i++) eco.procesar(bloque,2,48000);
        long inicio=System.nanoTime();
        for(int i=0;i<1000;i++) eco.procesar(bloque,2,48000);
        double ms=(System.nanoTime()-inicio)/1e6;
        comprobar(ms < 1000*1024/48.0,"El DSP no alcanza tiempo real");
        pasa(String.format(Locale.ROOT,"DSP: %.3f ms/bloque de 21.333 ms",ms/1000));
    }

    private static void probarRelojYCancelacion() throws Exception {
        ServicioDecodificador servicio=new ServicioDecodificador(); servicio.prepararReproduccion();
        LineaSimulada audio=new LineaSimulada();
        audio.open(new AudioFormat(48000,16,2,true,false));
        audio.escritos=audio.reproducidos=5928;
        servicio.usarRelojAudio(audio,500000);
        comprobar(servicio.getTiempoActualUs()==623500,"Video no sigue posicion real del audio");
        servicio.finalizarSalidaAudio(); long anterior=servicio.getTiempoActualUs(); Thread.sleep(5);
        comprobar(servicio.getTiempoActualUs()>anterior,"El reloj quedo congelado al terminar el audio");
        audio.close();
        for(int i=0;i<30;i++) servicio.encolarAudioProcesado(new ServicioDecodificador.ContenedorAudioProcesado(new byte[4],2,48000,0));
        Thread productor=new Thread(()->{
            try { servicio.encolarAudioProcesado(new ServicioDecodificador.ContenedorAudioProcesado(new byte[4],2,48000,0)); }
            catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
        });
        productor.start(); Thread.sleep(60);
        comprobar(productor.isAlive(),"La cola no aplica contrapresion");
        servicio.detener(); productor.join(1000);
        comprobar(!productor.isAlive(),"Productor bloqueado tras cancelar una cola llena");
        servicio.finalizarReproduccion();
        pasa("reloj maestro, continuacion sin audio y cancelacion con cola llena");
    }

    static final class VistaPrueba extends VistaReproductor {
        volatile int imagenes;
        volatile boolean activa;
        volatile boolean falloImagen;
        @Override public void actualizarImagenVideo(BufferedImage imagen) {
            if(imagen==null) falloImagen=true;
            imagenes++;
        }
        @Override public void actualizarTelemetria(ServicioDecodificador.Metricas metricas) {}
        @Override public void setReproduciendo(boolean activa) { this.activa=activa; }
    }

    // Reloj y contrapresion equivalentes a un dispositivo PCM; write devuelve
    // fragmentos pequenos para comprobar que el controlador escribe todo el bloque.
    static final class LineaSimulada implements SourceDataLine {
        AudioFormat formato;
        int capacidad;
        volatile boolean abierta, iniciada, drenando;
        long escritos, reproducidos, ultimoNanos;
        double fraccion;
        int precarga, vaciados;
        long drenados;
        public synchronized void open(AudioFormat formato,int capacidad) {
            this.formato=formato; this.capacidad=capacidad; abierta=true;
        }
        public void open(AudioFormat formato) { open(formato,(int)formato.getSampleRate()*formato.getFrameSize()/5); }
        public void open() {}
        private synchronized void avanzar() {
            long ahora=System.nanoTime();
            if(iniciada) {
                double frames=(ahora-ultimoNanos)*formato.getSampleRate()/1e9+fraccion;
                long disponibles=escritos-reproducidos;
                long consumir=Math.min((long)frames,disponibles);
                if(!drenando && disponibles>0 && frames>disponibles+formato.getSampleRate()*.005) vaciados++;
                reproducidos+=consumir;
                fraccion=(consumir==disponibles) ? 0 : frames-consumir;
            }
            ultimoNanos=ahora;
        }
        public int write(byte[] datos,int offset,int longitud) {
            while(abierta) {
                synchronized(this) {
                    avanzar();
                    int libres=capacidad-(int)(escritos-reproducidos)*formato.getFrameSize();
                    int cantidad=Math.min(Math.min(libres,longitud),257*formato.getFrameSize());
                    cantidad-=cantidad%formato.getFrameSize();
                    if(cantidad>0) { escritos+=cantidad/formato.getFrameSize(); return cantidad; }
                }
                try { Thread.sleep(1); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); return 0; }
            }
            return 0;
        }
        public void drain() {
            drenando=true;
            while(abierta) {
                synchronized(this) { avanzar(); if(reproducidos>=escritos) { drenados=reproducidos; return; } }
                try { Thread.sleep(1); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); return; }
            }
        }
        public synchronized void start() { precarga=(int)(escritos-reproducidos); ultimoNanos=System.nanoTime(); iniciada=true; }
        public synchronized void stop() { avanzar(); iniciada=false; }
        public synchronized void flush() { reproducidos=escritos; }
        public synchronized void close() { abierta=false; iniciada=false; }
        public synchronized int available() { avanzar(); return capacidad-(int)(escritos-reproducidos)*formato.getFrameSize(); }
        public synchronized long getLongFramePosition() { avanzar(); return reproducidos; }
        public int getFramePosition() { return (int)getLongFramePosition(); }
        public long getMicrosecondPosition() { return getLongFramePosition()*1000000/(long)formato.getSampleRate(); }
        public AudioFormat getFormat() { return formato; }
        public int getBufferSize() { return capacidad; }
        public float getLevel() { return 0; }
        public boolean isRunning() { return iniciada; }
        public boolean isActive() { return iniciada; }
        public boolean isOpen() { return abierta; }
        public Line.Info getLineInfo() { return new DataLine.Info(SourceDataLine.class,formato); }
        public Control[] getControls() { return new Control[0]; }
        public boolean isControlSupported(Control.Type tipo) { return false; }
        public Control getControl(Control.Type tipo) { throw new IllegalArgumentException(); }
        public void addLineListener(LineListener listener) {}
        public void removeLineListener(LineListener listener) {}
    }

    static final class ControladorPrueba extends ControladorReproductor {
        final List<LineaSimulada> lineas=new ArrayList<>();
        ControladorPrueba(VistaPrueba vista,ServicioDecodificador servicio) { super(vista,servicio); }
        @Override protected SourceDataLine crearLineaAudio(AudioFormat formato) {
            LineaSimulada linea=new LineaSimulada(); lineas.add(linea); return linea;
        }
    }

    private static boolean hayHilos() {
        return Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive() &&
                Set.of("Hilo Decodificacion","Hilo Decodificacion Audio","Hilo Procesamiento Audio","Hilo Audio","Hilo Renderizado",
                        "Hilo Supervisor","Hilo Telemetria","Hilo Cierre").contains(t.getName()));
    }
    private static void esperarFin(ServicioDecodificador servicio,long limiteMs) throws Exception {
        long limite=System.nanoTime()+limiteMs*1000000;
        while((servicio.isEjecutando() || hayHilos()) && System.nanoTime()<limite) Thread.sleep(5);
        comprobar(!servicio.isEjecutando() && !hayHilos(),"Quedaron hilos activos o un consumidor no termino");
    }
    private static long[] contar(File archivo) throws Exception {
        long video=0,audio=0;
        try(var grabber=new FFmpegFrameGrabber(archivo)) {
            grabber.start(); org.bytedeco.javacv.Frame frame;
            while((frame=grabber.grab())!=null) {
                if(frame.image!=null) video++;
                if(frame.samples!=null) audio+=frame.samples.length>1 ? frame.samples[0].remaining()
                        : frame.samples[0].remaining()/frame.audioChannels;
            }
        }
        return new long[]{video,audio};
    }
    private static void integraciones(File carpeta) throws Exception {
        VistaPrueba[] holder=new VistaPrueba[1];
        SwingUtilities.invokeAndWait(()->holder[0]=new VistaPrueba());
        VistaPrueba vista=holder[0];
        ServicioDecodificador servicio=new ServicioDecodificador();
        ControladorPrueba controlador=new ControladorPrueba(vista,servicio);
        try {
            for(String nombre:new String[]{"normal.mp4","normal.mp4","corto.mp4","sin_audio.mp4",
                    "solo_audio.m4a","audio_corto.mp4","audio_tardio.mp4"}) {
                File archivo=new File(carpeta,nombre);
                long[] esperado=contar(archivo);
                int antes=vista.imagenes, lineasAntes=controlador.lineas.size();
                controlador.iniciarReproduccion(archivo); esperarFin(servicio,10000);
                int imagenes=vista.imagenes-antes;
                comprobar(!vista.falloImagen && imagenes<=esperado[0],"Imagen no valida o cola de video contaminada");
                comprobar(imagenes>=Math.ceil(esperado[0]*.8),"Se perdieron demasiados fotogramas: "+nombre+" "+imagenes+"/"+esperado[0]);
                if(esperado[1]>0) {
                    comprobar(controlador.lineas.size()==lineasAntes+1,"No se abrio la salida de audio");
                    LineaSimulada linea=controlador.lineas.get(lineasAntes);
                    long total=esperado[1]+(long)(linea.formato.getSampleRate()/10);
                    comprobar(linea.escritos==total,"Se truncaron bloques PCM: "+nombre+" "+linea.escritos+"/"+total);
                    comprobar(linea.drenados==total && !linea.abierta,"Audio sin drenar o dispositivo sin cerrar");
                    comprobar(linea.precarga>=Math.min(total,(long)(linea.formato.getSampleRate()/10)),"Audio sin precarga");
                    comprobar(linea.vaciados==0,"Underflow en el dispositivo simulado");
                }
                pasa("reproduccion " + nombre + ": " + imagenes + "/" + esperado[0] + " imagenes, EOF y cierre");
            }
            controlador.iniciarReproduccion(new File(carpeta,"normal.mp4"));
            Thread.sleep(150); controlador.detenerReproduccion(); esperarFin(servicio,2000);
            pasa("cancelacion durante reproduccion");
            controlador.iniciarReproduccion(new File(carpeta,"no_existe.mp4")); esperarFin(servicio,2000);
            pasa("error de apertura sin hilos huerfanos");
            controlador.iniciarReproduccion(new File(carpeta,"normal.mp4")); Thread.sleep(150);
            SwingUtilities.invokeAndWait(()->vista.dispatchEvent(new WindowEvent(vista,WindowEvent.WINDOW_CLOSING)));
            esperarFin(servicio,2000); SwingUtilities.invokeAndWait(()->{});
            comprobar(!vista.isDisplayable(),"Cerrar la ventana no libero la sesion");
            pasa("cierre de ventana durante reproduccion");
        } finally {
            controlador.detenerReproduccion();
            SwingUtilities.invokeAndWait(vista::dispose);
        }
    }
    private static void hardware(File carpeta) throws Exception {
        VistaPrueba[] holder=new VistaPrueba[1];
        SwingUtilities.invokeAndWait(()->holder[0]=new VistaPrueba());
        VistaPrueba vista=holder[0];
        ServicioDecodificador servicio=new ServicioDecodificador();
        class ControladorHardware extends ControladorReproductor {
            final java.util.concurrent.atomic.AtomicInteger inicios=new java.util.concurrent.atomic.AtomicInteger();
            volatile java.util.concurrent.CountDownLatch cierre;
            ControladorHardware() { super(vista,servicio); }
            @Override protected SourceDataLine crearLineaAudio(AudioFormat formato) throws LineUnavailableException {
                SourceDataLine linea=super.crearLineaAudio(formato);
                var cerrado=new java.util.concurrent.CountDownLatch(1);
                cierre=cerrado;
                linea.addLineListener(evento->{
                    if(evento.getType()==LineEvent.Type.START) inicios.incrementAndGet();
                    if(evento.getType()==LineEvent.Type.CLOSE) cerrado.countDown();
                });
                return linea;
            }
        }
        ControladorHardware controlador=new ControladorHardware();
        try {
            for(String nombre:new String[]{"normal.mp4","normal.mp4","corto.mp4","solo_audio.m4a","audio_corto.mp4"}) {
                long[] esperado=contar(new File(carpeta,nombre));
                int antes=vista.imagenes;
                controlador.inicios.set(0); controlador.cierre=null;
                controlador.iniciarReproduccion(new File(carpeta,nombre));
                esperarFin(servicio,10000);
                comprobar(controlador.cierre!=null && controlador.cierre.await(1,java.util.concurrent.TimeUnit.SECONDS),
                        "El dispositivo real no se abrio o no se cerro");
                comprobar(controlador.inicios.get()==1,"Interrupcion/reinicio del dispositivo real: " + controlador.inicios.get());
                comprobar(vista.imagenes-antes>=Math.ceil(esperado[0]*.8),"Desincronizacion con hardware real");
                pasa("hardware real, silencio, un inicio continuo y cierre: " + nombre);
            }
        } finally {
            controlador.detenerReproduccion();
            SwingUtilities.invokeAndWait(vista::dispose);
        }
    }

    public static void main(String[] args) throws Exception {
        try {
            org.bytedeco.ffmpeg.global.avutil.av_log_set_level(-8);
            probarCopiasYPCM(); probarEco(); probarRelojYCancelacion();
            if (args.length > 0) {
                integraciones(new File(args[0]));
            } else {
                System.out.println("Pruebas basicas completadas. Para ejecutar las integraciones, indique la carpeta con los archivos multimedia de prueba.");
            }
            if(args.length>1 && args[1].equals("--hardware")) hardware(new File(args[0]));
            System.out.println("RESULTADO: " + pruebas + " comprobaciones superadas");
        } catch(Throwable ex) { ex.printStackTrace(); System.exit(1); }
    }
}
