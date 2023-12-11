package prlprg;

import java.util.ArrayList;
import java.util.List;

import prlprg.Generator.Con;
import prlprg.Generator.Inst;
import prlprg.Generator.Tuple;
import prlprg.Generator.Type;

// Generate subtypes of any given type, one a time, with a given fuel.
class Subtyper {

    Generator gen;

    Subtyper(Generator gen) {
        this.gen = gen;
    }

    TypeGen make(Type t, Fuel f) {
        return switch (t) {
            case Con con ->
                new ConGen(con, f);
            case Inst inst ->
                new InstGen(inst, f);
            case Tuple tup ->
                new TupleGen(tup, f);
            default ->
                throw new RuntimeException("Unknown type: " + t);
        };
    }

    static class Fuel {

        int n;

        Fuel(int n) {
            this.n = n;
        }

        boolean isZero() {
            return n == 0;
        }

        Fuel dec() {
            return new Fuel(n - 1);
        }
    }

    class TypeGen {

        Type next;
        Fuel f;

        TypeGen(Type t, Fuel f) {
            this.next = t;
            this.f = f;
        }

        boolean hasNext() {
            return !f.isZero() && next != null;
        }

        Type next() {
            return null;
        }
    }

    class TupleGen extends TypeGen {

        Tuple t;
        List<TypeGen> tyGens = new ArrayList<>();
        List<Type> tys = new ArrayList<>();

        TupleGen(Tuple t, Fuel f) {
            super(null, f);
            this.t = t;
            for (var ty : t.tys()) {
                var tg = make(ty, f);
                if (!tg.hasNext()) {
                    next = null;
                    return;
                }
                tyGens.add(tg);
                tys.add(tg.next());
            }
            next = new Tuple(new ArrayList<>(tys));
        }

        // Returns the perviously generated type, and builds the next one.
        // If the next one is null, then we have exhausted all possible types.
        @Override
        public Type next() {
            var prev = next;
            for (int i = 0; i < tyGens.size(); i++) {
                var tg = tyGens.get(i);
                if (tg.hasNext()) {
                    tys.set(i, tg.next());
                    next = new Tuple(new ArrayList<>(tys));
                    return prev;
                } else {
                    tg = make(t.tys().get(i), f);
                    tyGens.set(i, tg);
                    tys.set(i, tg.next());
                }
            }
            next = null;
            return prev;
        }
    }

    // A parametric type, tries to generate all possible instantiations of the
    // parameters within budget, and for each generates all subtypes.
    class InstGen extends TypeGen {

        Inst inst;
        TupleGen argGen;
        KidGen kids;

        InstGen(Inst inst, Fuel f) {
            super(null, f);
            this.inst = inst;
            this.argGen = new TupleGen(new Tuple(inst.tys()), f);
            next = argGen.hasNext() ? new Inst(inst.nm(), ((Tuple) argGen.next()).tys()) : null;
        }

        @Override
        Type next() {
            var prev = next;
            if (kids == null) {
                kids = new KidGen((Inst) prev, f);
                if (kids.hasNext()) {
                    next = kids.next();
                    return prev;
                } else {
                    kids = null;
                    next = argGen.hasNext() ? new Inst(inst.nm(), ((Tuple) argGen.next()).tys()) : null;
                }
                return prev;
            } else if (kids.hasNext()) {
                next = kids.next();
                return prev;
            } else {
                kids = null;
                next = argGen.hasNext() ? new Inst(inst.nm(), ((Tuple) argGen.next()).tys()) : null;
                return prev;
            }
        }
    }

    class KidGen extends TypeGen {

        Inst inst;
        List<String> kids;
        InstGen tg;

        KidGen(Inst inst, Fuel f) {
            super(null, f);
            this.inst = inst;
            this.kids = gen.directInheritance.getOrDefault(inst.nm(), new ArrayList<>());
            if (!kids.isEmpty()) {
                var it = new Inst(kids.removeFirst(), inst.tys());
                tg = new InstGen(it, f);
                next = tg.next();
            }
        }

        @Override
        Type next() {
            var prev = next;
            if (tg.hasNext()) {
                next = tg.next();
            } else if (!kids.isEmpty()) {
                var it = new Inst(kids.removeFirst(), inst.tys());
                tg = new InstGen(it, f);
                next = tg.next();
            } else {
                next = null;
            }
            return prev;
        }
    }

    class ConGen extends TypeGen {

        ConGen(Con con, Fuel f) {
            super(con, f);
        }

        @Override
        Type next() {
            var prev = next;
            next = null;
            return prev;
        }
    }

    public static void main(String[] args) {
        App.debug = true;
        var p = new Parser().withString(tstr);
        while (!p.isEmpty()) {
            Freshener.reset();
            App.db.addTyDecl(TypeDeclaration.parse(p.sliceLine()).toTy());
        }
        p = new Parser().withString(str);
        while (!p.isEmpty()) {
            Freshener.reset();
            App.db.addSig(Function.parse(p.sliceLine()).toTy());
        }
        App.db.cleanUp();
        var gen = new Generator(App.db);
        gen.gen();
        var sub = new Subtyper(gen);
        var tup = new Tuple(List.of(new Inst("A", List.of()), new Inst("B", List.of()), new Inst("C", List.of())));
        var tg = sub.make(tup, new Fuel(10));
        while (tg.hasNext()) {
            System.out.println(tg.next());
        }
    }

    static String tstr = """
    abstract type A end
    abstract type B end
    abstract type AA <: A end
    abstract type BB <: B end
    abstract type BC <: B end
    """;
    static String str = """
    function ceil(::T) where T>:Missing
    """;

}
