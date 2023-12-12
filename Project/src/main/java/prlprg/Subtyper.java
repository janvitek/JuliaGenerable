package prlprg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.crypto.SealedObject;

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
            this.next = tyGens.get(0).next();
        }

        @Override
        public boolean hasNext() {
            return super.hasNext();
        }

        @Override
        public Type next() {
            var prev = next;
            for (int i=0; i<tyGens.size(); i++) {
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
        InstGen tg = null;

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
            var res =  (Inst) unifyDeclToInstance(d, new Tuple(inst_arg_tys));           
            tg = new InstGen(res, f);
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
    //  \Exist x. B{Vector{Int}, x} 
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
     Type unifyDeclToInstance(Decl d, Tuple t){
        if (!(d.ty() instanceof Exist)) {
            return (Inst) d.ty(); // nothing to do, it is an instance, the cast is just sanity checking
        }
        var lhs =(Exist) d.ty(); 
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
        for (int i=0; i<rhsargs.size(); i++) {
            var rhsarg = rhsargs.get(i);
            var uparg = upargs.get(i);
            if (!unifyType(rhsarg, uparg, subst)) {
                throw new RuntimeException("Failed to unify " + rhsarg + " with " + uparg);
            }
        }
        var res = substitute(lhs, subst);        
        return res;
    }

    Type substitute(Type t, HashMap<Bound,Type> subst) {
        return switch (t) {
            case Var v -> {
               var res = subst.get(v.b());
               yield res == null ? v : res;
            }
            case Con c -> c;
            case Inst i -> new Inst(i.nm(), i.tys().stream().map(ty -> substitute(ty, subst)).collect(Collectors.toCollection(ArrayList::new)));
            case Tuple p -> new Tuple(p.tys().stream().map(ty -> substitute(ty, subst)).collect(Collectors.toCollection(ArrayList::new)));
            case Union u -> new Union(u.tys().stream().map(ty -> substitute(ty, subst)).collect(Collectors.toCollection(ArrayList::new)));
            case Exist e -> {
                var found = subst.get(e.b());
                if (found == null) {
                    var up = substitute(e.b().up(), subst);
                    var low = substitute(e.b().low(), subst);
                    var ty = substitute(e.ty(), subst);
                    yield new Exist(new Bound(e.b().nm(), up, low), ty);
                } else {
                    yield substitute(e.ty(), subst);
                }
            }
            default -> throw new RuntimeException("Unknown type: " + t);
        };
    }

    // unifyType( A{Vector{Int}}, A{Vector{Int}}, {} )
    // unifyType( A{Vector{T}}, A{Vector{Int}}, {T -> Int} )
    // unifyType( A{T}, A{Vector{Int}}, {T -> Vector{Int}} )
    boolean unifyType(Type t, Type u, HashMap<Bound, Type> subst) {
        return    switch (t) {
            case Var v -> { 
                var ty = subst.get(v.b());
                if (ty == null) {
                    subst.put(v.b(), u);
                }
                // expect that multiple occurences of a var are ok
                // keep the first binding. Revisit if we should check for equality
                yield true;
            }
            case Con c -> u instanceof Con && c.nm().equals(((Con) u).nm());
            case Inst i -> {
                if (u instanceof Inst ui) {
                    if (!i.nm().equals(ui.nm())) {
                        yield false;
                    }
                    var uis = ui.tys();
                    if (i.tys().size() != uis.size()) {
                        yield false;
                    }
                    for (int j=0; j<i.tys().size(); j++) {
                        if (!unifyType(i.tys().get(j), uis.get(j), subst)) {
                            yield false;
                        }
                    }
                    yield true;
                }  else { yield false;}
            }
            case Tuple p -> {
                if (u instanceof Tuple up) {
                    if (p.tys().size() != up.tys().size()) {
                        yield false;
                    }
                    for (int j=0; j<p.tys().size(); j++) {
                        if (!unifyType(p.tys().get(j), up.tys().get(j), subst)) {
                            yield false;
                        }
                    }
                    yield true;
                }  else { yield false;}
            }
            case Union tu -> {
                if (u instanceof Union un) {
                    if (tu.tys().size() != un.tys().size()) {
                        yield false;
                    }
                    for (int j=0; j<tu.tys().size(); j++) {
                        if (!unifyType(tu.tys().get(j), un.tys().get(j), subst)) {
                            yield false;
                        }
                    }
                    yield true;
                }  else { yield false;}
            }
            case Exist e -> {
                if (u instanceof Exist) {
                    // That is hard, for now ignore this case. Revisit?
                    yield true;
                }  else { yield false;}
            }
            default -> false;
        };        
    }

    void failIf(boolean b) {
        if (b) {
            throw new RuntimeException("Fail");
        }
    }

Set<Bound> freeVars(Type t) {
    return switch (t) {
        case Var v -> Set.of(v.b());
        case Con c -> Set.of();
        case Inst i -> i.tys().stream().flatMap(ty -> freeVars(ty).stream()).collect(Collectors.toCollection(HashSet::new));
        case Tuple p -> p.tys().stream().flatMap(ty -> freeVars(ty).stream()).collect(Collectors.toCollection(HashSet::new));
        case Union u -> u.tys().stream().flatMap(ty -> freeVars(ty).stream()).collect(Collectors.toCollection(HashSet::new));
        case Exist e -> {
            var up = freeVars(e.b().up());
            var low = freeVars(e.b().low());
            var free = new HashSet<Bound>(freeVars(e.ty()));
            free.remove(e.b()) ; 
            free.addAll(up);
            free.addAll(low);
            yield free;
        }
        default -> throw new RuntimeException("Unknown type: " + t);
    };
}

    List<Bound> grabLeadingBounds(Exist e) {
        var bounds = new ArrayList<Bound>();
        var ty = e.ty();
        while (ty instanceof Exist) {
            var ex = (Exist) ty;
            bounds.add(ex.b());
            ty = ex.ty();
        }
        return bounds;
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
    abstract type F{T} end
    abstract type G{T} <: F{T} end
    abstract type H{T} end
    abstract type HH{T} <: H{Vector{T}} end
    abstract type Int end
    """;
    static String str = """
    function a(::A, ::A)
    function a(::A, ::B, ::A)
    function a(::Tuple{AA,D}, ::BB)
    function a(::Union{AA,D}, ::BC)
    function a(::F{Int})
    function a(::H{Vector{Int}})
    """;

}
