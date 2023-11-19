public class App {

    static void parseTypes() {
        var parser = new Parser().withFile("../Inputs/types.jlg");
        while (!parser.peek().isEOF()) {
            var typ = TypeDeclaration.parse(parser);
            if (typ == null)
                break;
            System.out.println(typ);
        }
    }

    static void parseFunctions(Parser parser) {
        while (!parser.peek().isEOF())
            Function.parse(parser);
    }

    static boolean debug = false;

    public static void main(String[] args) {
        parseTypes();
        if (debug)
            parseFunctions(new Parser()
                    .withString("function Str{T}(arg1) where In <: T <: Real"));
        else
            parseFunctions(new Parser().withFile("../Inputs/morefunctions.jlg"));
    }
}