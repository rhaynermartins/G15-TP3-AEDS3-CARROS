import compressao.ArquivoComprimido;
import compressao.ArquivoComprimido.Algoritmo;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Oráculos: SHA-256 e Files.mismatch; casos binários não dependem da entidade Carro. */
public class TesteCompressao {
    private static Path pasta;
    private static int numero, verificacoes;
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("build-test"));
        pasta = Files.createTempDirectory(Path.of("build-test"), "compressao-");
        byte[] alfabeto = new byte[256]; for (int i = 0; i < 256; i++) alfabeto[i] = (byte) i;
        Random random = new Random(315);
        byte[] grande = new byte[2_000_000]; random.nextBytes(grande);
        byte[] repetido = new byte[200000]; Arrays.fill(repetido, (byte) 255);
        List<byte[]> casos = new ArrayList<>(List.of(new byte[0], new byte[]{0}, new byte[]{(byte)255},
                new byte[10000], alfabeto, repetido, "ABABABA|ação|Toyota\0".repeat(10000).getBytes(java.nio.charset.StandardCharsets.UTF_8), grande));
        for (byte[] caso : casos) testar(caso, true);
        // Cruzam repetidamente os limites de dicionário e o caso especial KwKwK.
        for (int i = 0; i < 150; i++) {
            byte[] bytes = new byte[random.nextInt(12000)];
            random.nextBytes(bytes);
            if (i % 2 == 0) for (int j = 0; j < bytes.length; j++) bytes[j] &= 3;
            testar(bytes, false);
        }
        System.out.println("COMPRESSÃO: " + numero + " entradas x 2 algoritmos; " + verificacoes
                + " verificações; vazio/único/256 bytes/zeros/KwKwK/reset/2 MB aleatórios: OK");
        System.out.println("CORRUPÇÃO: truncamento, cabeçalho, SHA, frequência/payload, padding e lixo adicional: OK");
        System.out.println("TODOS OS TESTES DE COMPRESSÃO TP3 PASSARAM.");
    }
    private static void testar(byte[] bytes, boolean testarCorrupcao) throws Exception {
        int n = ++numero;
        Path original = pasta.resolve("original-" + n);
        Files.write(original, bytes);
        for (Algoritmo a : Algoritmo.values()) {
            Path comprimido = pasta.resolve(n + "-" + a), restaurado = pasta.resolve(n + "-" + a + "-restaurado");
            ArquivoComprimido.comprimir(original, comprimido, a, 1);
            var h = ArquivoComprimido.descomprimir(comprimido, restaurado);
            exigir(h.tamanho() == bytes.length && h.versao() == 1 && h.algoritmo() == a, "Metadados");
            exigir(Files.mismatch(original, restaurado) == -1, "Igualdade byte a byte");
            exigir(Arrays.equals(ArquivoComprimido.digest(original), ArquivoComprimido.digest(restaurado)), "SHA");
            byte[] pronto = Files.readAllBytes(comprimido);
            falha(() -> ArquivoComprimido.comprimir(original, comprimido, a, 2));
            exigir(Arrays.equals(pronto, Files.readAllBytes(comprimido)), "Destino existente preservado");
            if (testarCorrupcao) {
                for (int tamanho : new int[]{0, 4, 20, pronto.length - 1})
                    corrompido(Arrays.copyOf(pronto, tamanho));
                for (int pos : new int[]{0, 4, 8, 9, 13, 21, pronto.length - 1}) {
                    byte[] errado = pronto.clone(); errado[pos] ^= (byte) 128; corrompido(errado);
                }
                byte[] lixo = Arrays.copyOf(pronto, pronto.length + 1); corrompido(lixo);
            }
            Files.delete(comprimido); Files.delete(restaurado);
        }
        Files.delete(original);
    }
    private static void corrompido(byte[] bytes) throws Exception {
        Path fonte = pasta.resolve("invalido"), saida = pasta.resolve("nao-publicar");
        Files.write(fonte, bytes);
        falha(() -> ArquivoComprimido.descomprimir(fonte, saida));
        exigir(!Files.exists(saida), "Saída inválida não pode permanecer");
    }
    private static void exigir(boolean condicao, String motivo) {
        verificacoes++; if (!condicao) throw new AssertionError(motivo);
    }
    @FunctionalInterface private interface Acao { void executar() throws Exception; }
    private static void falha(Acao acao) throws Exception {
        try { acao.executar(); } catch (IOException | IllegalArgumentException esperado) { verificacoes++; return; }
        throw new AssertionError("Operação inválida aceita");
    }
}
