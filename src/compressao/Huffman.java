package compressao;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Huffman estático: duas passagens e árvore reproduzível por frequência/símbolo. */
final class Huffman {
    private record No(long frequencia, int minimo, No esquerda, No direita) {
        boolean folha() { return esquerda == null; }
    }
    private static No arvore(long[] frequencias) {
        PriorityQueue<No> fila = new PriorityQueue<>(Comparator.comparingLong(No::frequencia).thenComparingInt(No::minimo));
        for (int i = 0; i < 256; i++)
            if (frequencias[i] > 0) fila.add(new No(frequencias[i], i, null, null));
        while (fila.size() > 1) {
            No a = fila.remove(), b = fila.remove();
            fila.add(new No(a.frequencia + b.frequencia, Math.min(a.minimo, b.minimo), a, b));
        }
        return fila.poll();
    }
    private static void codigos(No no, String prefixo, String[] tabela) {
        if (no.folha()) tabela[no.minimo] = prefixo.isEmpty() ? "0" : prefixo;
        else { codigos(no.esquerda, prefixo + "0", tabela); codigos(no.direita, prefixo + "1", tabela); }
    }
    static void comprimir(Path fonte, DataOutputStream out) throws IOException {
        long[] frequencias = new long[256];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(fonte))) {
            int b; while ((b = in.read()) >= 0) frequencias[b]++;
        }
        // Frequências inteiras preservam a árvore, inclusive em empates.
        for (long n : frequencias) out.writeLong(n);
        No raiz = arvore(frequencias);
        String[] tabela = new String[256];
        if (raiz != null) codigos(raiz, "", tabela);
        FluxoBits.Escritor bits = new FluxoBits.Escritor(out);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(fonte))) {
            int b;
            while ((b = in.read()) >= 0)
                for (char c : tabela[b].toCharArray()) bits.escrever(c - '0', 1);
        }
        bits.terminar();
    }
    static void descomprimir(DataInputStream in, OutputStream out, long tamanho) throws IOException {
        long[] frequencias = new long[256], observadas = new long[256];
        long soma = 0;
        for (int i = 0; i < 256; i++) {
            long n = in.readLong();
            if (n < 0 || n > tamanho - soma) throw new IOException("Frequências Huffman inválidas.");
            soma += n; frequencias[i] = n;
        }
        if (soma != tamanho) throw new IOException("Tamanho Huffman divergente.");
        No raiz = arvore(frequencias);
        FluxoBits.Leitor bits = new FluxoBits.Leitor(in);
        for (long i = 0; i < tamanho; i++) {
            No no = raiz;
            // Símbolo único ainda usa um bit por ocorrência, evitando ambiguidade.
            if (no.folha()) {
                if (bits.ler(1) != 0) throw new IOException("Código de símbolo único inválido.");
            } else while (!no.folha()) no = bits.ler(1) == 0 ? no.esquerda : no.direita;
            out.write(no.minimo); observadas[no.minimo]++;
        }
        if (!Arrays.equals(frequencias, observadas)) throw new IOException("Frequências restauradas divergentes.");
        bits.validarFim();
    }
}
