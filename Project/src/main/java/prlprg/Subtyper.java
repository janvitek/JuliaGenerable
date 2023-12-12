package prlprg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import prlprg.Generator.Bound;
import prlprg.Generator.Con;
import prlprg.Generator.Decl;
import prlprg.Generator.Exist;
import prlprg.Generator.Inst;
import prlprg.Generator.Tuple;
import prlprg.Generator.Type;
import prlprg.Generator.Union;
import prlprg.Generator.Var;

// Generate subtypes of any given type, one a time, with a given fuel.
class Subtyper {

    Generator gen;

    Subtyper(Generator gen) {
        this.gen = gen;
    }

    TypeGen make(Type t, Fuel f) {
        failIf(!freeVars(t).isEmpty());
        return switch (t) {
            case Con con ->
                new ConGen(con, f);
            case Inst inst ->
                new InstGen(inst, f);
            case Tuple tup ->
                new TupleGen(tup, f);
            case Union u ->
                new UnionGen(u, f);
            case Exist e ->
                new ExistGen(e, f);
            default ->
                throw new RuntimeException("Unexpected type: " + t); // E,g, Var
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

    class UnionGen extends TypeGen {

        List<TypeGen> tyGens = new ArrayList<>();
        int off = 0;

        UnionGen(Union u, Fuel f) {
            super(null, f);
            this.tyGens = u.tys().stream().map(ty -> make(ty, f)).collect(Collectors.toCollection(ArrayList::new));
            this.next = tyGens.isEmpty() ? null : tyGens.get(0).next();
        }

        @Override
        public boolean hasNext() {
            return super.hasNext();
        }

        @Override
        public Type next() {
            var prev = next;
            for (int i = 0; i < tyGens.size(); i++) {
                var offset = (off + i) % tyGens.size();
                var tg = tyGens.get(offset);
                if (tg.hasNext()) {
                    next = tg.next();
                    return prev;
                }
            }
            next = null;
            return prev;
        }
    }

    class TupleGen extends TypeGen {

        final Tuple t;
        List<TypeGen> tyGens;
        final List<Type> tys;

        TupleGen(Tuple t, Fuel f) {
            super(null, f);
            this.t = t;
            this.tyGens = t.tys().stream().map(ty -> make(ty, f)).collect(Collectors.toCollection(ArrayList::new));
            this.tys = tyGens.stream().map(tg -> tg.next()).collect(Collectors.toCollection(ArrayList::new));
            this.next = new Tuple(new ArrayList<>(tys));
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
                    tg = make(t.tys().get(i), f);
                    tyGens.set(i, tg);
                    tys.set(i, tg.next());
                }
            }
            next = null;
            return prev;
        }
    }

    class ExistGen extends TypeGen {

        final Exist e; // the original existential
        TypeGen boundGen; // the generator for the bound values
        TypeGen currTypeGen; // the generator for the body of the existential

        ExistGen(Exist e, Fuel f) {
            super(null, f);
            this.e = e; // recall the orignal existential, this should not be modified
            this.boundGen = make(e.b().up(), f); // make the generator for the bound values, ignoring lower bounds
            this.next = makeNext();
            failIf(next == null); // sanity check
        }

        private Type subst(Type t, Bound b, Type repl) {
            return switch (t) {
                case Var v ->
                    v.b().equals(b) ? repl : v;
                case Con c ->
                    c;
                case Inst i ->
                    new Inst(i.nm(), i.tys().stream().map(ty -> subst(ty, b, repl)).collect(Collectors.toCollection(ArrayList::new)));
                case Tuple p ->
                    new Tuple(p.tys().stream().map(ty -> subst(ty, b, repl)).collect(Collectors.toCollection(ArrayList::new)));
                case Union u ->
                    new Union(u.tys().stream().map(ty -> subst(ty, b, repl)).collect(Collectors.toCollection(ArrayList::new)));
                case Exist e -> {
                    var up = subst(e.b().up(), b, repl);
                    var low = subst(e.b().low(), b, repl);
                    var ty = subst(e.ty(), b, repl);
                    var newbound = new Bound(e.b().nm(), low, up);
                    ty = subst(ty, e.b(), new Var(newbound));
                    yield new Exist(newbound, ty);
                }
                default ->
                    throw new RuntimeException("Unknown type: " + t);
            };
        }

        private Type makeNext() {
            if (boundGen.hasNext()) {
                var repl = boundGen.next(); // grab the next bound value
                var sub = subst(e.ty(), e.b(), repl); // substitute it into the body of the existential
                currTypeGen = make(sub, f); // create a generator for the body of the existential
                return currTypeGen.next(); // get its first value
            }
            return null;
        }

        @Override
        Type next() {
            var prev = next;
            next = currTypeGen.hasNext() ? currTypeGen.next() : makeNext();
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
            this.next = nextInstWithArgsOrNull();
        }

        private Inst nextInstWithArgsOrNull() {
            return argGen.hasNext() ? new Inst(inst.nm(), ((Tuple) argGen.next()).tys()) : null;
        }

        @Override
        Type next() {
            var prev = next;
            // kids == null when we have not yet started generating kids for this inst{args} combo
            // in this case, prev == inst{args}
            kids = kids == null ? new KidGen((Inst) prev, f) : kids;
            kids = kids.hasNext() ? kids : null; //if there are no more kids, reset the kids generator
            next = (kids != null && kids.hasNext()) ? kids.next() : nextInstWithArgsOrNull();
            return prev;
        }
    }

    class KidGen extends TypeGen {

        final List<Type> inst_arg_tys;
        final List<String> kids;
        TypeGen tg = null;

        KidGen(Inst inst, Fuel f) {
            super(null, f);
            this.inst_arg_tys = new ArrayList<>(inst.tys());
            this.kids = new ArrayList<>(gen.directInheritance.getOrDefault(inst.nm(), new ArrayList<>()));
            this.next = grabNextKidOrNull();
        }

        private Type grabNextKidOrNull() {
            if (kids.isEmpty()) {
                return null;
            }
            var nm = kids.removeFirst();
            var d = gen.decls.get(nm);
            failIf(d == null);
            var res = unifyDeclToInstance(d, new Tuple(inst_arg_tys));
            tg = make(res, f);
            return tg.next();
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

    // Attempt to bind variables in a subtype. Use case is that we want to generate
    // subtypes of an instance A{Vector{Int}}, the instance does not have free variables,
    // and we have Decls:
    //    abstract type A{T} end
    //    abstract type B{T,N} <: A{T} end
    //    abstract type C{T} <: A{Vector{T}} end
    // The types we should generate are:
    //  ∃x. B{Vector{Int}, x}
    //  C{Int}
    // The algorithm unifies the tuple of types in the query with the arguments of the rhs
    // (i.e. the parent) and returns the lhs with the bound variables substituted.
    //
    // This implementation performs structural unification, which is not always correct so
    // we may fail to unify in some cases. For example, if the query is:
    //    A{Union{Int, Float64}}
    // and the decl is:
    //   abstract type B{T} <: A{Union{Float64,T}} end
    // To get that we would have to do some normalization of type terms. Given the complexity
    // of the type language, it is not clear how far to go. These are rare cases, perhaps we
    // could complain and let humans deal with it.
    //
    // d.ty =   ∃T1.∃T2. ... ∃Tn. A{T1, T2, ... Tn}
    // d.inst = B{exp1, exp2, ... expn}
    //
    // The Ti are all distinct (i.e. no shadowing allowed in Julia for parameters in type declarations))
    //
    // We return:
    //   ∃T1. ... ∃Tn-m. A{exp1, ... expn}
    //
    // Where some of the exps are variables and others are instances
    Type unifyDeclToInstance(Decl d, Tuple t) {
        if (!(d.ty() instanceof Exist)) {
            return (Inst) d.ty(); // nothing to do, it is an instance, the cast is just sanity checking
        }
        var lhs = (Exist) d.ty();
        var rhsargs = d.inst().tys();
        var upargs = t.tys();
        // sanity checking
        failIf(rhsargs.size() != upargs.size());
        failIf(!freeVars(lhs).isEmpty());
        var freerhs = freeVars(t);
        failIf(!freerhs.isEmpty());
        //   rhs may have free variables defined in lhs
        var bounds = grabLeadingBounds(lhs);
        freerhs.removeAll(bounds);
        failIf(!freerhs.isEmpty());
        // sane.
        var subst = new HashMap<Bound, Type>();
        // subst maps bounds to instances without free variables
        for (int i = 0; i < rhsargs.size(); i++) {
            var rhsarg = rhsargs.get(i);
            var uparg = upargs.get(i);
            if (!unifyType(rhsarg, uparg, subst)) {
                throw new RuntimeException("Failed to unify " + rhsarg + " with " + uparg);
            }
        }
        for (var ty : subst.values()) {
            failIf(!freeVars(ty).isEmpty());
        }
        var res = substitute(lhs, subst);
        failIf(!freeVars(res).isEmpty());
        return res;
    }

    // Type t has form:
    //     ∃T1.∃T2. ... ∃Tn. A{T1, T2, ... Tn}
    // We removes some of the existentials.
    Type substitute(Type t, HashMap<Bound, Type> subst) {
        return switch (t) {
            case Var v ->
                subst.containsKey(v.b()) ? subst.get(v.b()) : v;
            case Inst i ->
                new Inst(i.nm(), i.tys().stream().map(ty -> substitute(ty, subst)).collect(Collectors.toCollection(ArrayList::new)));
            case Exist e -> {
                if (!subst.containsKey(e.b())) {
                    var up = substitute(e.b().up(), subst);
                    var low = substitute(e.b().low(), subst);
                    var newsubst = new HashMap<Bound, Type>(subst);
                    var newbound = new Bound(e.b().nm(), low, up);
                    newsubst.put(e.b(), new Var(newbound));
                    var ty = substitute(e.ty(), newsubst);
                    yield new Exist(newbound, ty);
                } else {
                    yield substitute(e.ty(), subst);
                }
            }
            case Tuple p ->
                new Tuple(p.tys().stream().map(ty -> substitute(ty, subst)).collect(Collectors.toCollection(ArrayList::new)));
            case Union u ->
                new Union(u.tys().stream().map(ty -> substitute(ty, subst)).collect(Collectors.toCollection(ArrayList::new)));
            case Con c ->
                c;
            default ->
                throw new RuntimeException("Unexpected type: " + t);
        };
    }

    // unifyType  A{Vector{Int}}, A{Vector{Int}}  ==  {}
    // unifyType  A{Vector{T}},   A{Vector{Int}}  ==  {T -> Int}
    // unifyType  A{T},           A{Vector{Int}}  ==  {T -> Vector{Int}}
    boolean unifyType(Type t, Type u, HashMap<Bound, Type> subst) {
        return switch (t) {
            case Var v -> {
                if (subst.containsKey(v.b())) {
                    var b = subst.get(v.b());
                    failIf(!u.structuralEquals(b)); // this is too strict, there are equivalent types that will
                } else { // not be structually equal. Revist when you start seeing failures.
                    subst.put(v.b(), u);
                }
                yield true;
            }
            case Con c ->
                u.structuralEquals(c);
            case Inst i -> {
                if (u instanceof Inst ui) {
                    if (!i.nm().equals(ui.nm()) || i.tys().size() != ui.tys().size()) {
                        yield false;
                    }
                    for (int j = 0; j < i.tys().size(); j++) {
                        if (!unifyType(i.tys().get(j), ui.tys().get(j), subst)) {
                            yield false;
                        }
                    }
                    yield true;
                }
                yield false;
            }
            case Tuple p -> {
                if (u instanceof Tuple up) {
                    if (p.tys().size() != up.tys().size()) {
                        yield false;
                    }
                    for (int j = 0; j < p.tys().size(); j++) {
                        if (!unifyType(p.tys().get(j), up.tys().get(j), subst)) {
                            yield false;
                        }
                    }
                    yield true;
                }
                yield false;
            }
            case Union tu -> {
                if (u instanceof Union un) {
                    if (tu.tys().size() != un.tys().size()) {
                        yield false;
                    }
                    for (int j = 0; j < tu.tys().size(); j++) {
                        if (!unifyType(tu.tys().get(j), un.tys().get(j), subst)) {
                            yield false;
                        }
                    }
                    yield true;
                }
                yield false;
            }
            case Exist e -> {
                if (u instanceof Exist) {
                    // That is hard, for now ignore this case. Revisit?
                    yield true;
                }
                yield false;
            }
            default ->
                false;
        };
    }

    void failIf(boolean b) {
        if (b) {
            throw new RuntimeException("Fail");
        }
    }

    Set<Bound> freeVars(Type t) {
        return switch (t) {
            case Var v ->
                Set.of(v.b());
            case Inst i ->
                i.tys().stream().flatMap(ty -> freeVars(ty).stream()).collect(Collectors.toCollection(HashSet::new));
            case Tuple p ->
                p.tys().stream().flatMap(ty -> freeVars(ty).stream()).collect(Collectors.toCollection(HashSet::new));
            case Union u ->
                u.tys().stream().flatMap(ty -> freeVars(ty).stream()).collect(Collectors.toCollection(HashSet::new));
            case Exist e -> {
                var free = new HashSet<Bound>(freeVars(e.ty())); // to make the set mutable
                free.remove(e.b());
                free.addAll(freeVars(e.b().up()));
                free.addAll(freeVars(e.b().low()));
                yield free;
            }
            default ->
                Set.of();
        };
    }

    // e of the form ∃T1.∃T2. ... ∃Tn. A{T1, T2, ... Tn}
    // returns [T1, T2, ... Tn]
    List<Bound> grabLeadingBounds(Exist e) {
        var bounds = new ArrayList<Bound>();
        var ty = e.ty();
        while (ty instanceof Exist ex) {
            bounds.add(ex.b());
            ty = ex.ty();
        }
        return bounds;
    }

    public static void main(String[] args) {
        //test(tstr, str);
        test3();
    }

    static void test1() {
        var tys = """
    abstract type A end
    abstract type B end
    abstract type AA <: A end
    """;
        var funs = """
    function a(::T) where T<:A
    """;
        test(tys, funs);
    }

    static void test2() {
        var tys = """
    abstract type A end
    abstract type B end
    abstract type AA <: A end
    """;
        var funs = """
    function a(::T)  where T<:Any
    """;
        test(tys, funs);
    }

    static void test3() {
        var tys = """
    abstract type A end
    abstract type B{T<:A} end
    abstract type AA <: A end
    """;
        var funs = """
    function a(::Any)
    """;
        test(tys, funs);
    }

    static void test(String tstr, String str) {
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
        var g = new Generator(App.db);
        g.gen();
        var sub = new Subtyper(g);
        var functions = g.sigs.get("a");
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
    abstract type Int end
    abstract type F{T} end
    abstract type G{T} <: F{T} end
    abstract type H{T} end
    abstract type HH{T} <: H{Vector{T}} end
    abstract type Vector{T} end
    """;
    static String str = """
    function a(::A, ::A)
    function a(::A, ::B, ::A)
    function a(::Tuple{AA,D}, ::BB)
    function a(::Union{AA,D}, ::BC)
    function a(::F{Int})
    function a(::H{Vector{Int}})
    function a(::T)  where T<:G{Int}
    """;

}
