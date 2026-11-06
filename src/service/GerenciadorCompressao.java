package service;

import compressao.ArquivoComprimido;
import compressao.ArquivoComprimido.Algoritmo;
import compressao.ArquivoComprimido.Cabecalho;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Versiona o par sobre um único snapshot e só entrega restaurações verificadas ao TP2. */
public final class GerenciadorCompressao {
    private final Path db, pasta;
    private final GerenciadorIndices indices;
    public record Medicao(Algoritmo algoritmo, long original, long bytes, long nanos) {
        public double ganho() { return original == 0 ? Double.NaN : (original - bytes) * 100.0 / original; }
        @Override public String toString() {
            return algoritmo + ": bytes=" + bytes + "; ganho=" + (original == 0 ? "n/a (original vazio)"
                    : String.format(Locale.ROOT, "%.2f%%", ganho())) + "; tempo_ns=" + nanos;
        }
    }
    public record Resultado(int versao, Medicao huffman, Medicao lzw) {
        @Override public String toString() {
            return "Versão=" + versao + "; original=" + huffman.original + " bytes\n" + huffman + "\n" + lzw
                    + "\nMenor arquivo: " + melhor(huffman.bytes, lzw.bytes)
                    + "; compressão mais rápida: " + melhor(huffman.nanos, lzw.nanos);
        }
    }
    private static String melhor(long h, long l) { return h == l ? "empate" : h < l ? "Huffman" : "LZW"; }

    public GerenciadorCompressao(Path db, Path pasta, GerenciadorIndices indices) {
        this.db = db.toAbsolutePath().normalize(); this.pasta = pasta.toAbsolutePath().normalize();
        this.indices = Objects.requireNonNull(indices);
    }
    public Path arquivo(int versao, Algoritmo algoritmo) {
        if (versao < 1) throw new IllegalArgumentException("Versão deve ser positiva.");
        return pasta.resolve(db.getFileName() + algoritmo.toString() + "Compressao" + versao);
    }
    /** Inclui versões incompletas: uma interrupção nunca autoriza reutilizar seus nomes. */
    public List<Integer> versoes() throws IOException {
        SortedSet<Integer> resultado = new TreeSet<>();
        if (!Files.exists(pasta)) return List.of();
        Pattern nomes = Pattern.compile(Pattern.quote(db.getFileName().toString()) + "(?:Huffman|LZW)Compressao([1-9][0-9]*)");
        Pattern reservas = Pattern.compile("\\.versao-([1-9][0-9]*)\\.pendente");
        try (DirectoryStream<Path> filhos = Files.newDirectoryStream(pasta)) {
            for (Path p : filhos) {
                Matcher m = nomes.matcher(p.getFileName().toString());
                if (!m.matches()) m = reservas.matcher(p.getFileName().toString());
                if (m.matches()) {
                    try { resultado.add(Integer.parseInt(m.group(1))); }
                    catch (NumberFormatException e) { throw new IOException("Número de versão fora do limite.", e); }
                }
            }
        }
        return List.copyOf(resultado);
    }
    public String listarVersoes() throws IOException {
        StringBuilder texto = new StringBuilder();
        for (int v : versoes()) texto.append("Versão ").append(v).append(": ")
                .append(Files.isRegularFile(arquivo(v, Algoritmo.Huffman))
                        && Files.isRegularFile(arquivo(v, Algoritmo.LZW)) && !Files.exists(reserva(v))
                        ? "par disponível (integridade conferida ao descomprimir)" : "incompleta; não restaurável")
                .append('\n');
        return texto.length() == 0 ? "Nenhuma compressão criada." : texto.toString();
    }
    private Path reserva(int v) { return pasta.resolve(".versao-" + v + ".pendente"); }
    private Path temporario(String prefixo) throws IOException {
        Path p = Files.createTempFile(pasta, prefixo, ".tmp");
        Files.delete(p); return p; // Codec exige CREATE_NEW.
    }
    @SuppressWarnings("try")
    public Resultado comprimir() throws IOException {
        try (BloqueioBase lock = BloqueioBase.adquirir(db)) {
            Files.createDirectories(pasta);
            List<Integer> existentes = versoes();
            int max = existentes.isEmpty() ? 0 : existentes.get(existentes.size() - 1);
            if (max == Integer.MAX_VALUE) throw new IOException("Limite de versões atingido.");
            int v = max + 1;
            Path marca = reserva(v);
            Files.createFile(marca);
            Path snapshot = null, h = null, l = null;
            boolean publicadoH = false, publicadoL = false, completo = false;
            try {
                snapshot = Files.createTempFile(pasta, ".snapshot-", ".tmp");
                byte[] antes = ArquivoComprimido.digest(db);
                Files.copy(db, snapshot, StandardCopyOption.REPLACE_EXISTING);
                if (!Arrays.equals(antes, ArquivoComprimido.digest(snapshot)))
                    throw new IOException("Snapshot divergente; base em alteração.");
                h = temporario(".huffman-"); l = temporario(".lzw-");
                long inicio = System.nanoTime();
                ArquivoComprimido.comprimir(snapshot, h, Algoritmo.Huffman, v);
                long tempoH = System.nanoTime() - inicio;
                inicio = System.nanoTime();
                ArquivoComprimido.comprimir(snapshot, l, Algoritmo.LZW, v);
                long tempoL = System.nanoTime() - inicio;
                if (!Arrays.equals(antes, ArquivoComprimido.digest(db)))
                    throw new IOException("Base alterada durante compressão.");
                Resultado resultado = new Resultado(v,
                        new Medicao(Algoritmo.Huffman, Files.size(snapshot), Files.size(h), tempoH),
                        new Medicao(Algoritmo.LZW, Files.size(snapshot), Files.size(l), tempoL));
                // Sem REPLACE_EXISTING: uma publicação concorrente não é sobrescrita.
                Files.move(h, arquivo(v, Algoritmo.Huffman)); publicadoH = true;
                Files.move(l, arquivo(v, Algoritmo.LZW)); publicadoL = true;
                Files.delete(marca); completo = true;
                return resultado;
            } finally {
                if (!completo) {
                    if (publicadoH) Files.deleteIfExists(arquivo(v, Algoritmo.Huffman));
                    if (publicadoL) Files.deleteIfExists(arquivo(v, Algoritmo.LZW));
                    Files.deleteIfExists(marca);
                }
                for (Path p : new Path[]{snapshot, h, l}) if (p != null) Files.deleteIfExists(p);
            }
        }
    }

    public Preparacao prepararRestauracao(int v) throws IOException {
        BloqueioBase lock = BloqueioBase.adquirir(db);
        Path h = null, l = null;
        boolean entregue = false;
        try {
            if (Files.exists(reserva(v))) throw new IOException("Versão interrompida/incompleta.");
            h = temporario(".restauracao-h-"); l = temporario(".restauracao-l-");
            long inicio = System.nanoTime();
            Cabecalho ch = ArquivoComprimido.descomprimir(arquivo(v, Algoritmo.Huffman), h);
            long tempoH = System.nanoTime() - inicio;
            inicio = System.nanoTime();
            Cabecalho cl = ArquivoComprimido.descomprimir(arquivo(v, Algoritmo.LZW), l);
            long tempoL = System.nanoTime() - inicio;
            if (ch.algoritmo() != Algoritmo.Huffman || cl.algoritmo() != Algoritmo.LZW
                    || ch.versao() != v || cl.versao() != v || ch.tamanho() != cl.tamanho()
                    || !ch.sha256().equals(cl.sha256()) || Files.mismatch(h, l) != -1)
                throw new IOException("Par comprimido pertence a bases/versões diferentes.");
            Preparacao p = new Preparacao(lock, h, l, tempoH, tempoL, ch.sha256(), ArquivoComprimido.digest(db));
            entregue = true; return p;
        } finally {
            if (!entregue) {
                try {
                    if (h != null) Files.deleteIfExists(h);
                    if (l != null) Files.deleteIfExists(l);
                } finally { lock.close(); }
            }
        }
    }

    /** Mantém lock e temporários durante a escolha humana; close também permite cancelar. */
    public final class Preparacao implements AutoCloseable {
        private final BloqueioBase lock;
        private final Path h, l;
        private final String hash;
        private final byte[] estado;
        public final long huffmanNanos, lzwNanos;
        private boolean fechada, restaurada;
        private Preparacao(BloqueioBase lock, Path h, Path l, long th, long tl, String hash, byte[] estado) {
            this.lock = lock; this.h = h; this.l = l; huffmanNanos = th; lzwNanos = tl;
            this.hash = hash; this.estado = estado;
        }
        public String resumo() {
            return "Descompressão Huffman_ns=" + huffmanNanos + "; LZW_ns=" + lzwNanos
                    + "; mais rápida=" + melhor(huffmanNanos, lzwNanos)
                    + "\nIntegridade: tamanho, SHA-256 e igualdade byte a byte: OK; SHA-256=" + hash;
        }
        public void restaurar(Algoritmo algoritmo) throws IOException {
            if (fechada || restaurada) throw new IOException("Preparação encerrada.");
            Objects.requireNonNull(algoritmo);
            if (!Arrays.equals(estado, ArquivoComprimido.digest(db))) throw new IOException("Base mudou após preparação.");
            Path escolhido = algoritmo == Algoritmo.Huffman ? h : l;
            if (!HexFormat.of().formatHex(ArquivoComprimido.digest(escolhido)).equals(hash))
                throw new IOException("Temporário mudou após validação.");
            indices.restaurarBase(escolhido); restaurada = true;
        }
        @Override public void close() throws IOException {
            if (fechada) return;
            fechada = true;
            try { Files.deleteIfExists(h); Files.deleteIfExists(l); }
            finally { lock.close(); }
        }
    }
}
