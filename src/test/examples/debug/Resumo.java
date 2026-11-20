import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

class Resumo {
    enum Status { AUTORIZADA, CANCELADA }

    static class Base {
        Long id = 812L;
    }

    static class Item {
        String descricao;
        Item(String descricao) { this.descricao = descricao; }
    }

    static class Nota extends Base {
        static final long serialVersionUID = 1L;
        Integer numero = 9005;
        String serie = "0065";
        Status status = Status.AUTORIZADA;
        BigDecimal valor = new BigDecimal("45.90");
        List<Item> itens = new ArrayList<>(List.of(new Item("a"), new Item("b"), new Item("c"), new Item("d")));
    }

    public static void main(String[] args) {
        Nota nota = new Nota();
        Item item = new Item("parafuso");
        System.out.println(nota.numero + item.descricao);
    }
}
