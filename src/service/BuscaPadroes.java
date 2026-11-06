package service;

import dao.ArquivoSequencial;
import model.Carro;
import padrao.KMP;
import padrao.BMH;
import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;

/** Pesquisa apenas nomes desserializados ativos, nunca bytes de controle. */
public final class BuscaPadroes {
    public enum Metodo { KMP, BMH }
    public record Item(int id, String nome) {}
    public record Resultado(Metodo metodo, List<Item> itens, long nanos) {
        public Resultado { itens = List.copyOf(itens); }
        public int total() { return itens.size(); }
        public List<Item> pagina(int inicio) {
            if (inicio < 0 || inicio > total()) throw new IllegalArgumentException("Página inválida.");
            return itens.subList(inicio, Math.min(total(), inicio + 20));
        }
    }
    private final ArquivoSequencial dados;
    public BuscaPadroes(ArquivoSequencial dados) { this.dados = dados; }

    @SuppressWarnings("try")
    public Resultado buscar(String padrao, Metodo metodo) throws IOException {
        if (padrao == null || padrao.isEmpty()) throw new IllegalArgumentException("Padrão vazio não é permitido.");
        Objects.requireNonNull(metodo);
        try (BloqueioBase lock = BloqueioBase.adquirir(dados.getCaminho())) {
            long inicio = System.nanoTime();
            String normal = padrao.toLowerCase(Locale.ROOT);
            Predicate<String> busca = metodo == Metodo.KMP ? new KMP(normal)::encontrar : new BMH(normal)::encontrar;
            List<Item> encontrados = new ArrayList<>();
            dados.percorrerAtivos((id, pos) -> {
                Carro carro = dados.readAtPosition(pos, id);
                if (busca.test(carro.getNome().toLowerCase(Locale.ROOT))) encontrados.add(new Item(id, carro.getNome()));
            });
            return new Resultado(metodo, encontrados, System.nanoTime() - inicio);
        }
    }
}
