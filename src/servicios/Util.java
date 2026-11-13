package servicios;

import java.nio.*;

public final class Util {
    private Util() {}

    // Copia independiente sin modificar posicion, limite ni marca del original.
    public static Buffer duplicarBuffer(Buffer original) {
        if (original instanceof FloatBuffer b) return FloatBuffer.allocate(b.remaining()).put(b.duplicate()).flip();
        if (original instanceof ShortBuffer b) return ShortBuffer.allocate(b.remaining()).put(b.duplicate()).flip();
        if (original instanceof ByteBuffer b) return ByteBuffer.allocate(b.remaining()).order(b.order()).put(b.duplicate()).flip();
        if (original instanceof IntBuffer b) return IntBuffer.allocate(b.remaining()).put(b.duplicate()).flip();
        if (original instanceof DoubleBuffer b) return DoubleBuffer.allocate(b.remaining()).put(b.duplicate()).flip();
        if (original instanceof LongBuffer b) return LongBuffer.allocate(b.remaining()).put(b.duplicate()).flip();
        if (original instanceof CharBuffer b) return CharBuffer.allocate(b.remaining()).put(b.duplicate()).flip();
        throw new IllegalArgumentException("Tipo de buffer no soportado: " + original.getClass());
    }

    // PCM de 16 bits little-endian, con todos los canales entrelazados.
    public static byte[] convertirMuestrasABytesPCM(Buffer[] buffers, int canales) {
        if (buffers == null || buffers.length == 0) return new byte[0];
        if (canales <= 0 || (buffers.length != 1 && buffers.length != canales))
            throw new IllegalArgumentException("Distribucion de canales no valida");
        boolean planar = buffers.length > 1;
        int muestras = buffers[0].remaining();
        if (!planar && muestras % canales != 0)
            throw new IllegalArgumentException("Bloque PCM incompleto");
        if (planar) {
            for (Buffer buffer : buffers) {
                if (buffer.remaining() != muestras)
                    throw new IllegalArgumentException("Canales con distinta cantidad de muestras");
            }
        }
        int frames = planar ? muestras : muestras / canales;
        int totalMuestras = Math.multiplyExact(frames, canales);
        ByteBuffer pcm = ByteBuffer.allocate(Math.multiplyExact(totalMuestras, 2)).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < frames; frame++) {
            for (int canal = 0; canal < canales; canal++) {
                Buffer buffer = buffers[planar ? canal : 0];
                int indice = buffer.position() + (planar ? frame : frame * canales + canal);
                pcm.putShort((short) leerMuestra(buffer, indice));
            }
        }
        return pcm.array();
    }

    private static int leerMuestra(Buffer buffer, int indice) {
        if (buffer instanceof ShortBuffer b) return b.get(indice);
        if (buffer instanceof ByteBuffer b) return ((b.get(indice) & 0xff) - 128) << 8;
        if (buffer instanceof IntBuffer b) return b.get(indice) >> 16;
        double valor;
        if (buffer instanceof FloatBuffer b) valor = b.get(indice);
        else if (buffer instanceof DoubleBuffer b) valor = b.get(indice);
        else throw new IllegalArgumentException("Formato de muestra no soportado: " + buffer.getClass());
        if (!Double.isFinite(valor)) valor = 0;
        return (int) Math.max(-32768, Math.min(32767, Math.round(valor * 32768.0)));
    }

    // Una instancia por reproduccion, utilizada exclusivamente por el hilo DSP.
    // Retardo de 100 ms medido en frames de audio, independiente del tamano de bloque.
    public static final class ProcesadorEco {
        private short[] historial;
        private int posicion;
        private int canales;
        private int frecuencia;

        public byte[] procesar(Buffer[] muestras, int canales, int frecuencia) {
            if (canales <= 0 || frecuencia <= 0)
                throw new IllegalArgumentException("Formato de audio no valido");
            if (historial == null) {
                this.canales = canales;
                this.frecuencia = frecuencia;
                historial = new short[Math.multiplyExact(Math.max(1, frecuencia / 10), canales)];
            } else if (this.canales != canales || this.frecuencia != frecuencia) {
                throw new IllegalArgumentException("El formato de audio cambio durante la reproduccion");
            }
            byte[] pcm = convertirMuestrasABytesPCM(muestras, canales);
            aplicar(pcm);
            return pcm;
        }

        private void aplicar(byte[] pcm) {
            ShortBuffer muestras = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            for (int i = 0; i < muestras.limit(); i++) {
                short original = muestras.get(i);
                short anterior = historial[posicion];
                muestras.put(i, (short) ((original + anterior) / 2));
                historial[posicion] = original;
                posicion = (posicion + 1) % historial.length;
            }
        }

        // Conserva la ultima repeticion del eco al llegar al fin del archivo.
        public byte[] finalizar() {
            if (historial == null) return new byte[0];
            byte[] colaEco = new byte[historial.length * 2];
            aplicar(colaEco);
            return colaEco;
        }
    }
}
