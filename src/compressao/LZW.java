package compressao;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** LZW de 12 bits fixos: bytes 0..255, CLEAR=256, END=257, sequências 258..4095. */
final class LZW {
    private static final int CLEAR = 256, END = 257, INICIO = 258, LIMITE = 4096;
    static void comprimir(Path fonte, OutputStream out) throws IOException {
        Map<Integer, Integer> dicionario = new HashMap<>();
        FluxoBits.Escritor bits = new FluxoBits.Escritor(out);
        int seguinte = INICIO;
        bits.escrever(CLEAR, 12);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(fonte))) {
            int prefixo = in.read(), b;
            if (prefixo >= 0) {
                while ((b = in.read()) >= 0) {
                    int chave = (prefixo << 8) | b;
                    Integer codigo = dicionario.get(chave);
                    if (codigo != null) prefixo = codigo;
                    else {
                        bits.escrever(prefixo, 12);
                        if (seguinte < LIMITE) dicionario.put(chave, seguinte++);
                        else {
                            // CLEAR explícito mantém o decodificador sincronizado no limite.
                            bits.escrever(CLEAR, 12); dicionario.clear(); seguinte = INICIO;
                        }
                        prefixo = b;
                    }
                }
                bits.escrever(prefixo, 12);
            }
        }
        bits.escrever(END, 12); bits.terminar();
    }
    static void descomprimir(InputStream in, OutputStream out) throws IOException {
        FluxoBits.Leitor bits = new FluxoBits.Leitor(in);
        if (bits.ler(12) != CLEAR) throw new IOException("LZW sem CLEAR inicial.");
        int[] prefixos = new int[LIMITE];
        byte[] sufixos = new byte[LIMITE], pilha = new byte[LIMITE];
        int seguinte = INICIO, anterior = -1;
        while (true) {
            int codigo = bits.ler(12);
            if (codigo == END) break;
            if (codigo == CLEAR) { seguinte = INICIO; anterior = -1; continue; }
            boolean especial = codigo == seguinte && anterior >= 0;
            if (codigo >= seguinte && !especial) throw new IOException("Código LZW inexistente.");
            int atual = especial ? anterior : codigo, n = 0;
            // A cadeia de prefixos reconstitui a sequência em ordem inversa.
            while (atual >= INICIO) {
                if (atual >= seguinte || n >= LIMITE - 1) throw new IOException("Cadeia LZW inválida.");
                pilha[n++] = sufixos[atual]; atual = prefixos[atual];
            }
            if (atual < 0 || atual > 255) throw new IOException("Prefixo LZW inválido.");
            int primeiro = atual;
            pilha[n++] = (byte) primeiro;
            for (int j = n - 1; j >= 0; j--) out.write(pilha[j] & 255);
            // Caso KwKwK: a sequência nova é a anterior seguida de seu primeiro byte.
            if (especial) out.write(primeiro);
            if (anterior >= 0 && seguinte < LIMITE) {
                prefixos[seguinte] = anterior; sufixos[seguinte++] = (byte) primeiro;
            }
            anterior = codigo;
        }
        bits.validarFim();
    }
}
