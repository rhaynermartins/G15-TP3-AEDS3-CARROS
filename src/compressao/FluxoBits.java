package compressao;

import java.io.*;

/** Empacotamento MSB primeiro, sem conversões para texto. O último byte usa zeros. */
final class FluxoBits {
    static final class Escritor {
        private final OutputStream out;
        private int acumulado, quantidade;
        Escritor(OutputStream out) { this.out = out; }
        void escrever(int valor, int largura) throws IOException {
            for (int i = largura - 1; i >= 0; i--) {
                acumulado = (acumulado << 1) | ((valor >>> i) & 1);
                if (++quantidade == 8) {
                    out.write(acumulado); acumulado = quantidade = 0;
                }
            }
        }
        void terminar() throws IOException {
            if (quantidade > 0) out.write(acumulado << (8 - quantidade));
        }
    }
    static final class Leitor {
        private final InputStream in;
        private int atual, restantes;
        Leitor(InputStream in) { this.in = in; }
        int ler(int largura) throws IOException {
            int valor = 0;
            for (int i = 0; i < largura; i++) {
                if (restantes == 0) {
                    atual = in.read();
                    if (atual < 0) throw new EOFException("Fluxo de bits truncado.");
                    restantes = 8;
                }
                valor = (valor << 1) | ((atual >>> --restantes) & 1);
            }
            return valor;
        }
        void validarFim() throws IOException {
            if ((atual & ((1 << restantes) - 1)) != 0 || in.read() != -1)
                throw new IOException("Padding ou bytes excedentes no fluxo.");
        }
    }
}
