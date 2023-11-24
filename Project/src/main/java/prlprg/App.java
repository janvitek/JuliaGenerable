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
            var sig = Function.parse(parser);
            db.addSig(sig.toTy());
        }
    }

    static boolean debug = false;

    static GenDB db = new GenDB();

    public static void main(String[] args) {
        debug = false;
        var str = """
                 abstract type Any end
                 abstract type Random.Sampler{Y} end
                 struct Random.SamplerSimple{T, S, E} <: Random.Sampler{E} end
                """;
        var dir = "../Inputs/";
        var typs = dir + "types_from_jj.jlg";
        var parser = debug ? new Parser().withString(str) : new Parser().withFile(typs);
        parseTypes(parser);
        debug = false;
        str = """
              function f() @  asda/asds
              function ch(A::Stride{v } where v<:Union{  ComplexF64}, ::Type{LUp })
              """;
        var sigs = dir + "functions_from_jj.jlg";
        parser = debug ? new Parser().withString(str) : new Parser().withFile(sigs);
        parseFunctions(parser);
        db.cleanUp();
        var g = new Generator(db);
        g.processTypes();
        g.processSigs();
    }
}
