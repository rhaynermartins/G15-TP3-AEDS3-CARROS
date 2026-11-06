package padrao;

import java.util.Arrays;

/** BMH: compara da direita para a esquerda e desloca pelo último caractere da janela. */
public final class BMH {
    private final String padrao;
    private final int[] saltos = new int[Character.MAX_VALUE + 1];
    public BMH(String padrao) {
        if (padrao == null || padrao.isEmpty()) throw new IllegalArgumentException("Padrão vazio.");
        this.padrao = padrao;
        Arrays.fill(saltos, padrao.length());
        for (int i = 0; i < padrao.length() - 1; i++) saltos[padrao.charAt(i)] = padrao.length() - 1 - i;
    }
    public boolean encontrar(String texto) {
        int fim = padrao.length() - 1;
        while (fim < texto.length()) {
            int j = padrao.length() - 1, i = fim;
            while (j >= 0 && texto.charAt(i) == padrao.charAt(j)) { i--; j--; }
            if (j < 0) return true;
            int salto = saltos[texto.charAt(fim)];
            if (salto > texto.length() - 1 - fim) return false;
            fim += salto;
        }
        return false;
    }
}
