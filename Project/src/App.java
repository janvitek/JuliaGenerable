public class App {

    static void parseTypes() {
        var parser = new Parser().withFile("../Inputs/types.jlg");
        while (parser.peek() != null) {
            var typ = TypeDeclaration.parse(parser);
            if (typ == null)
                break;
            System.out.println(typ);
        }
    }

    static void parseFunctions(Parser parser) {
        while (parser.peek() != null)
            Function.parse(parser);
    }

    static boolean debug = false;

    public static void main(String[] args) {
        if (debug)
            parseFunctions(new Parser()
                    .withString("function size(a::Non{T,N,S} where {N}) where {T,S}"));
        else
            parseFunctions(new Parser().withFile("../Inputs/morefunctions.jlg"));
    }
}