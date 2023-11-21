package prlprg;

public class App {

    static void parseTypes(Parser parser) {
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

    static boolean debug = false;

    static GenDB db = new GenDB();

    public static void main(String[] args) {
        var parser = debug ? new Parser().withString("struct DataStructures.SSIncludeLast{ContainerType<:DataStructures.SortedSet} <: DataStructures.AbstractIncludeLast{ContainerType<:DataStructures.SortedSet} end")
                : new Parser().withFile("../Inputs/types_from_jj.jlg");
        parseTypes(parser);
        debug = false;
        if (debug) {
            parseFunctions(new Parser()
                    .withString("function Str{T}(arg1) where In <: T <: Real"));
        } else {
            parseFunctions(new Parser().withFile("../Inputs/functions_from_jj.jlg"));
        }

        db.cleanUp();
    }
}
