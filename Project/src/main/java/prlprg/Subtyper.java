package prlprg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import prlprg.Generator.Con;
import prlprg.Generator.Inst;
import prlprg.Generator.Tuple;
import prlprg.Generator.Type;
import prlprg.Generator.Union;

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
            case Union u ->
                new UnionGen(u, f);
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

        CombinerGen combiner;

        TupleGen(Tuple t, Fuel f) {
            super(null, f);
            this.combiner = new CombinerGen(t.tys(), f);
        }

        @Override
        public boolean hasNext() {
            return combiner.hasNext();
        }

        // Returns the perviously generated type, and builds the next one.
        // If the next one is null, then we have exhausted all possible types.
        @Override
        public Type next() {
            return combiner.next();
        }
    }

    class UnionGen extends TypeGen {

        CombinerGen combiner;

        UnionGen(Union u, Fuel f) {
            super(null, f);
            this.combiner = new CombinerGen(u.tys(), f);
        }

        @Override
        public boolean hasNext() {
            return combiner.hasNext();
        }

        @Override
        public Type next() {
            var t = (Tuple) combiner.next();
            return new Union(t.tys());
        }
    }

    class CombinerGen extends TypeGen {

        List<TypeGen> tyGens = new ArrayList<>();
        List<Type> tys = new ArrayList<>();

        CombinerGen(List<Type> ogtys, Fuel f) {
            super(null, f);
            this.tyGens = ogtys.stream().map(ty -> make(ty, f)).collect(Collectors.toCollection(ArrayList::new));
            this.tys = tyGens.stream().map(tg -> tg.next()).collect(Collectors.toCollection(ArrayList::new));
            this.next = new Tuple(new ArrayList<>(tys));
            nullCheck();
        }

        private void nullCheck() {
            var hasNull = tys.stream().anyMatch(ty -> ty == null);
            if (hasNull) {
                throw new RuntimeException("Null type in tys: " + tys);
            }
        }

        @Override
        Type next() {
            var prev = next;
            for (int i = 0; i < tyGens.size(); i++) {
                var tg = tyGens.get(i);
                if (tg.hasNext()) {
                    tys.set(i, tg.next());
                    next = new Tuple(new ArrayList<>(tys));
                    return prev;
                } else {
                    tg = make(tys.get(i), f);
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

        final Inst inst;
        final TupleGen argGen;
        KidGen kids;

        InstGen(Inst inst, Fuel f) {
            super(null, f);
            this.inst = (Inst) inst.deepClone(new HashMap<>());
            this.argGen = new TupleGen(new Tuple(inst.tys()), f);
            next = argGen.hasNext() ? new Inst(inst.nm(), ((Tuple) argGen.next()).tys()) : null;
        }

        @Override
        public boolean hasNext() {
            return super.hasNext() || (kids != null && kids.hasNext());
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
            } else {
                next = argGen.hasNext() ? new Inst(inst.nm(), ((Tuple) argGen.next()).tys()) : null;
                if (prev == null && next == null) {
                    return kids.next();
                }
                return prev;
            }
        }
    }

    class KidGen extends TypeGen {

        final Inst inst;
        final List<String> kids;
        InstGen tg;

        KidGen(Inst inst, Fuel f) {
            super(null, f);
            this.inst = (Inst) inst.deepClone(new HashMap<>());
            this.kids = new ArrayList<>(gen.directInheritance.getOrDefault(inst.nm(), new ArrayList<>()));
            this.next = grabNextKidOrNull();
        }

        private Type grabNextKidOrNull() {
            if (!kids.isEmpty()) {
                var it = new Inst(kids.removeFirst(), inst.tys());
                tg = new InstGen(it, f);
                return tg.next();
            } else {
                return null;
            }
        }

        @Override
        Type next() {
            var prev = next;
            next = tg.hasNext() ? tg.next() : grabNextKidOrNull();
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
        var functions = gen.sigs.get("a");
        for (var m : functions) {
            System.err.println("Generating subtypes of " + m);
            var tup = m.ty();
            var tg = sub.make(tup, new Fuel(10));
            while (tg.hasNext()) {
                System.out.println(tg.next());
            }
        }
    }

    static String tstr = """
    abstract type A end
    abstract type B end
    abstract type AA <: A end
    abstract type BB <: B end
    abstract type BC <: B end
    abstract type D end
    """;
    static String str = """
    function a(::B)
    function a(::A, ::B)
    function a(::Tuple{AA,D}, ::BB)
    function a(::Union{AA,D}, ::BC)
    """;

}
