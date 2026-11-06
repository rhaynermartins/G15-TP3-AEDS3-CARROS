package compressao;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Contêiner próprio: identifica algoritmo/versão e verifica TODOS os bytes originais. */
public final class ArquivoComprimido {
    public enum Algoritmo { Huffman, LZW }
    private static final int MAGIC = 0x54503343, FORMATO = 1;
    public record Cabecalho(Algoritmo algoritmo, int versao, long tamanho, String sha256) {}

    public static byte[] digest(Path caminho) throws IOException {
        MessageDigest md = sha();
        try (InputStream in = new DigestInputStream(new BufferedInputStream(Files.newInputStream(caminho)), md)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return md.digest();
    }
    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static Cabecalho cabecalho(Path caminho) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(caminho))) { return ler(in); }
    }
    private static Cabecalho ler(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC || in.readInt() != FORMATO) throw new IOException("Formato de compressão incompatível.");
        int algoritmo = in.readUnsignedByte(), versao = in.readInt();
        long tamanho = in.readLong();
        byte[] hash = new byte[32]; in.readFully(hash);
        if (algoritmo >= Algoritmo.values().length || versao < 1 || tamanho < 0)
            throw new IOException("Metadados de compressão inválidos.");
        return new Cabecalho(Algoritmo.values()[algoritmo], versao, tamanho, HexFormat.of().formatHex(hash));
    }
    public static void comprimir(Path fonte, Path destino, Algoritmo algoritmo, int versao) throws IOException {
        if (versao < 1) throw new IllegalArgumentException("Versão deve ser positiva.");
        long tamanho = Files.size(fonte);
        byte[] hash = digest(fonte);
        // CREATE_NEW impede sobrescrever versões existentes. Só removemos uma saída nossa.
        OutputStream criado = Files.newOutputStream(destino, StandardOpenOption.CREATE_NEW);
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(criado))) {
                out.writeInt(MAGIC); out.writeInt(FORMATO); out.writeByte(algoritmo.ordinal());
                out.writeInt(versao); out.writeLong(tamanho); out.write(hash);
                if (algoritmo == Algoritmo.Huffman) Huffman.comprimir(fonte, out);
                else LZW.comprimir(fonte, out);
            }
            if (Files.size(fonte) != tamanho || !Arrays.equals(hash, digest(fonte)))
                throw new IOException("Fonte alterada durante compressão.");
            try (RandomAccessFile f = new RandomAccessFile(destino.toFile(), "rw")) { f.getFD().sync(); }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(destino); throw e;
        }
    }
    public static Cabecalho descomprimir(Path fonte, Path destino) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(fonte)))) {
            Cabecalho h = ler(in);
            OutputStream criado = Files.newOutputStream(destino, StandardOpenOption.CREATE_NEW);
            try {
                MessageDigest md = sha();
                try (Conferidor out = new Conferidor(new DigestOutputStream(new BufferedOutputStream(criado), md), h.tamanho)) {
                    if (h.algoritmo == Algoritmo.Huffman) Huffman.descomprimir(in, out, h.tamanho);
                    else LZW.descomprimir(in, out);
                    if (out.quantidade != h.tamanho) throw new IOException("Tamanho restaurado divergente.");
                }
                if (!HexFormat.of().formatHex(md.digest()).equals(h.sha256))
                    throw new IOException("SHA-256 restaurado divergente.");
                return h;
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(destino); throw e;
            }
        }
    }
    /** Limita a expansão ao tamanho declarado antes de escrever bytes extras. */
    private static final class Conferidor extends FilterOutputStream {
        private final long limite;
        private long quantidade;
        Conferidor(OutputStream out, long limite) { super(out); this.limite = limite; }
        @Override public void write(int b) throws IOException {
            if (quantidade >= limite) throw new IOException("Expansão excede tamanho original.");
            out.write(b); quantidade++;
        }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (len > limite - quantidade) throw new IOException("Expansão excede tamanho original.");
            out.write(b, off, len); quantidade += len;
        }
    }
}
