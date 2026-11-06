import padrao.*;
import dao.ArquivoSequencial;
import model.Carro;
import service.BuscaPadroes;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** indexOf é apenas oráculo de teste; não participa de KMP ou BMH. */
public class TestePadroes {
    public static void main(String[] args) throws Exception {
        String[][] exemplos = {{"toyota corolla","toyota"}, {"toyota corolla","ota cor"}, {"toyota corolla","corolla"},
                {"aaaaaa","aaa"}, {"","a"}, {"abc","abcd"}, {"ação veículo","ação"}, {"abc","x"}, {"😀carro","😀"}};
        for (String[] par : exemplos) conferir(par[0], par[1]);
        Random r = new Random(173);
        String alfabeto = "abcde çãé\u0307";
        for (int i = 0; i < 20000; i++) {
            String texto = texto(r, alfabeto, r.nextInt(100)), padrao = texto(r, alfabeto, 1 + r.nextInt(18));
            conferir(texto, padrao);
        }
        try { new KMP(""); throw new AssertionError("KMP vazio"); } catch (IllegalArgumentException esperado) { }
        try { new BMH(""); throw new AssertionError("BMH vazio"); } catch (IllegalArgumentException esperado) { }
        Files.createDirectories(Path.of("build-test"));
        Path p = Files.createTempDirectory(Path.of("build-test"), "padroes-");
        ArquivoSequencial dados = new ArquivoSequencial(p.resolve("dados.db"));
        dados.reinicializar(35);
        for (int i = 1; i <= 35; i++) dados.appendComId(new Carro(i, "", "Toyota AÇÃO " + i,
                LocalDate.of(2026,11,6), List.of("automatic"), 2020));
        dados.delete(2);
        Carro movido = dados.read(3); movido.setNome("Toyota AÇÃO versão ampliada"); dados.update(movido);
        BuscaPadroes busca = new BuscaPadroes(dados);
        for (String termo : new String[]{"TOYOTA", "ação", "AÇÃO", "ampliada", "inexistente", "Toyota AÇÃO 1"}) {
            var a = busca.buscar(termo, BuscaPadroes.Metodo.KMP);
            var b = busca.buscar(termo, BuscaPadroes.Metodo.BMH);
            List<Integer> esperado = dados.listarAtivos().stream()
                    .filter(c -> c.getNome().toLowerCase(Locale.ROOT).contains(termo.toLowerCase(Locale.ROOT)))
                    .map(Carro::getId).toList();
            exigir(a.itens().stream().map(BuscaPadroes.Item::id).toList().equals(esperado), "Oráculo do arquivo");
            exigir(a.itens().equals(b.itens()), "Mesmo conjunto e ordem");
            exigir(a.pagina(0).size() <= 20, "Página limitada");
        }
        var todos = busca.buscar("toyota", BuscaPadroes.Metodo.KMP);
        exigir(todos.total() == 34 && todos.pagina(20).size() == 14, "Paginação/versões físicas");
        try { busca.buscar("", BuscaPadroes.Metodo.KMP); throw new AssertionError("Padrão vazio"); }
        catch (IllegalArgumentException esperado) { }
        System.out.println("PADRÕES: 20.009 pares comparados com indexOf; início/meio/fim/repetição/Unicode/ausente/maior: OK");
        System.out.println("BUSCA NO ARQUIVO: nomes ativos, case folding, acentos, lápides, realocação e páginas de 20: OK");
        System.out.println("TODOS OS TESTES DE PADRÕES TP3 PASSARAM.");
    }
    private static String texto(Random r, String alfabeto, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append(alfabeto.charAt(r.nextInt(alfabeto.length())));
        return s.toString();
    }
    private static void conferir(String texto, String padrao) {
        boolean esperado = texto.indexOf(padrao) >= 0;
        exigir(new KMP(padrao).encontrar(texto) == esperado, "KMP");
        exigir(new BMH(padrao).encontrar(texto) == esperado, "BMH");
    }
    private static void exigir(boolean b, String motivo) { if (!b) throw new AssertionError(motivo); }
}
