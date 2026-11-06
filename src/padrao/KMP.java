package padrao;

/** KMP: tabela de prefixos permite retomar o padrão sem voltar no texto. */
public final class KMP {
    private final String padrao;
    private final int[] prefixos;
    public KMP(String padrao) {
        if (padrao == null || padrao.isEmpty()) throw new IllegalArgumentException("Padrão vazio.");
        this.padrao = padrao; prefixos = new int[padrao.length()];
        for (int i = 1, j = 0; i < padrao.length(); i++) {
            while (j > 0 && padrao.charAt(i) != padrao.charAt(j)) j = prefixos[j - 1];
            if (padrao.charAt(i) == padrao.charAt(j)) j++;
            prefixos[i] = j;
        }
    }
    public boolean encontrar(String texto) {
        for (int i = 0, j = 0; i < texto.length(); i++) {
            while (j > 0 && texto.charAt(i) != padrao.charAt(j)) j = prefixos[j - 1];
            if (texto.charAt(i) == padrao.charAt(j)) j++;
            if (j == padrao.length()) return true;
        }
        return false;
    }
}
