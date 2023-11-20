package prlprg;

public class App {

    static void parseTypes() {
        var parser = new Parser().withFile("../Inputs/types.jlg");
        while (!parser.peek().isEOF()) {
            var typ = TypeDeclaration.parse(parser);
            db.addTyDecl(typ.toTy());
        }
    }

    static void parseFunctions(Parser parser) {
        while (!parser.peek().isEOF()) {
            Function.parse(parser);
        }
    }

    static boolean debug = true;

    static GenDB db = new GenDB();

    public static void main(String[] args) {
        parseTypes();
        if (debug) {
            parseFunctions(new Parser()
                    .withString("function Str{T}(arg1) where In <: T <: Real"));
        } else {
            parseFunctions(new Parser().withFile("../Inputs/morefunctions.jlg"));
        }

        db.cleanUp();
    }
}
