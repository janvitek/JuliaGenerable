module TypeDiscover

export typediscover

@enum Tag begin
    # functions
    Macro
    Lambda
    Constructor
    Callable
    Builtin
    Intrinsic
    Generic
    # types
    Closure
    SingletonFunctionType
    Typ
    # other
    Mod
end

abstract type Discovery end

struct FunctionDiscovery <: Discovery
    tag::Tag
    fun::Function
    meth::Method
    soft::Bool
end

struct TypeDiscovery <: Discovery
    tag::Tag
    mod::Module
    sym::Symbol
    type::Type
    soft::Bool
end

struct AliasDiscovery <: Discovery
    tag::Tag  # tag of what this aliases to
    mod::Module
    sym::Symbol
    what::Union{Function, Type, Module}
    soft::Bool
end

isalias(m::Module, s::Symbol, f::Function) = s != nameof(f) || m !== typeof(f).name.module

isalias(m::Module, s::Symbol, t::Type) = begin
    base = Base.unwrap_unionall(t)
    base isa Union || t !== base.name.wrapper || m !== base.name.module || string(s) != string(nameof(t))
end

isalias(m::Module, s::Symbol, other::Module) = s != nameof(other) || m !== parentmodule(other)

istagfunction(t::Tag) = Macro <= t <= Generic

istagtype(t::Tag) = Closure <= t <= Typ

istagmodule(t::Tag) = t == Mod

issoftimport(m::Module, s::Symbol) = m ∈ [Core, Base] && Base.isexported(m, s)

ismodulenested(m::Module, outer::Module) = begin
    sentinel = Base.moduleroot(m)
    while true
        m === outer && return true
        m === sentinel && return false
        m = parentmodule(m)
    end
end

tagof(f::Function)::Tag = begin
    # Core.IntrinsicFunction <: Core.Builtin so we need to check intrinsics first
    if f isa Core.IntrinsicFunction
        Intrinsic
    elseif f isa Core.Builtin
        Builtin
    else
        mt = typeof(f).name.mt
        hasname = isdefined(mt.module, mt.name) && typeof(getfield(mt.module, mt.name)) <: Function
        sname = string(mt.name)
        if hasname
            if startswith(sname, '@')
                Macro
            else
                Generic
            end
        elseif '#' in sname
            Lambda
        elseif mt === Base._TYPE_NAME.mt
            Constructor
        else
            Callable
        end
    end
end

tagof(t::Type)::Tag = begin
    if t <: Function
        if Base.issingletontype(t)
            SingletonFunctionType
        elseif occursin("var\"#", string(Base.unwrap_unionall(t)))
            Closure
        else
            Typ
        end
    else
        Typ
    end
end

discover(report::Function, modules::Vector{Module}) = begin
    visited = Set{Module}()
    methscache = Set{Method}()

    discoveraux(mod::Module, root::Module) = begin
        mod ∈ visited && return
        push!(visited, mod)

        for sym in names(mod; all=true, imported=true)
            val::Any = nothing
            try
                val = getproperty(mod, sym)
            catch e
                if e isa UndefVarError
                    GlobalRef(mod, sym) ∉ [GlobalRef(Base, :active_repl), GlobalRef(Base, :active_repl_backend),
                        GlobalRef(Base.Filesystem, :JL_O_TEMPORARY), GlobalRef(Base.Filesystem, :JL_O_SHORT_LIVED),
                        GlobalRef(Base.Filesystem, :JL_O_SEQUENTIAL), GlobalRef(Base.Filesystem, :JL_O_RANDOM)] &&
                        @warn "Module $mod exports symbol $sym but it's undefined."
                else
                    rethrow(e)
                end
            end

            if val isa Function && sym ∉ [:include, :eval]
                tag = tagof(val)
                soft = issoftimport(mod, sym)
                if isalias(mod, sym, val)
                    report(AliasDiscovery(tag, mod, sym, val, soft))
                end
                for m in methods(val)
                    m ∈ methscache && continue
                    push!(methscache, m)
                    any(mod -> ismodulenested(m.module, mod), modules) && report(FunctionDiscovery(tag, val, m, soft))
                end
            end

            if val isa Type && val ∉ [Union, UnionAll, DataType, Core.TypeofBottom, Union{}]
                tag = tagof(val)
                if isalias(mod, sym, val)
                    report(AliasDiscovery(tag, mod, sym, val, issoftimport(mod, sym)))
                else
                    report(TypeDiscovery(tag, mod, sym, val, issoftimport(mod, sym)))
                end
            end

            if val isa Module && val !== mod
                if isalias(mod, sym, val)
                    report(AliasDiscovery(Mod, mod, sym, val, issoftimport(mod, sym)))
                end
                if ismodulenested(val, root)
                    discoveraux(val, root)
                end
            end
        end
    end

    # make sure Any is always reported
    Core ∈ modules || report(TypeDiscovery(tagof(Any), Core, :Any, Any, issoftimport(Core, :Any)))

    for m in modules
        discoveraux(m, m)
    end
end

function BaseArgDeclPartsCustom(env, m::Method, html=false)
    tv = Any[]
    sig = m.sig
    while isa(sig, UnionAll)
        push!(tv, sig.var)
        sig = sig.body
    end
    file = m.file
    line = m.line
    argnames = Base.method_argnames(m)
    if length(argnames) >= m.nargs
        show_env = Base.ImmutableDict{Symbol, Any}()
        for kv in env
            show_env = Base.ImmutableDict(show_env, kv)
        end
        for t in tv
            show_env = Base.ImmutableDict(show_env, :unionall_env => t)
        end
        decls = Tuple{String,String}[Base.argtype_decl(show_env, argnames[i], sig, i, m.nargs, m.isva)
                                     for i = 1:m.nargs]
        decls[1] = ("", sprint(Base.show_signature_function, Base.unwrapva(sig.parameters[1]), false, decls[1][1], html,
                               context = show_env))
    else
        decls = Tuple{String,String}[("", "") for i = 1:length(sig.parameters::Core.SimpleVector)]
    end
    return tv, decls, file, line
end

baseShowMethodCustom(io::IO, d::FunctionDiscovery) = begin
    m = d.meth
    f = d.fun
    tag = d.tag
    kind = lowercase(string(tag))
    tv, decls, file, line = BaseArgDeclPartsCustom(io.dict, m)
    sig = Base.unwrap_unionall(m.sig)
    if sig === Tuple
        # Builtin
        print(io, m.module, ".", (tag == Intrinsic ? nameof(f) : m.name), "(...)  [", kind, "]")
        return
    end
    print(io, sig.parameters[1].name.module, ".", decls[1][2], "(")
    join(
        io,
        String[isempty(d[2]) ? d[1] : string(d[1], "::", d[2]) for d in decls[2:end]],
        ", ",
        ", ",
    )
    kwargs = Base.kwarg_decl(m)
    if !isempty(kwargs)
        print(io, "; ")
        join(io, map(Base.sym_to_string, kwargs), ", ", ", ")
    end
    print(io, ")")
    Base.show_method_params(io, tv)

    d.soft && print(io, "  @soft")

    print(io, "  [", kind)
    if line > 0
        file, line = Base.updated_methodloc(m)
        print(io, " @ ", file, ":", line)
    end
    print(io, "]")
end

baseShowTypeCustom(io::IO, @nospecialize(x::Type)) = begin
    if !Base.print_without_params(x)
        properx = Base.makeproper(io, x)
        if Base.make_typealias(properx) !== nothing || (Base.unwrap_unionall(x) isa Union && x <: Base.make_typealiases(properx)[2])
            Base.printstyled(IOContext(io, :compact => false), x)
            return
        end
    end
    show(io, x)
end

Base.show(io::IO, d::FunctionDiscovery) = begin
    @assert istagfunction(d.tag)
    print(io, "function ")
    baseShowMethodCustom(io, d)
end

Base.show(io::IO, d::TypeDiscovery) = begin
    @assert istagtype(d.tag)
    if d.tag == SingletonFunctionType
        show(io, d.type)
    elseif d.tag == Closure || d.tag == Typ
        print(io,
            if isabstracttype(d.type)
                "abstract type"
            elseif isstructtype(d.type)
                ismutabletype(d.type) ? "mutable struct" : "struct"
            elseif isprimitivetype(d.type)
                "primitive type"
            else
                "???"
            end)
        print(io, " ", Base.unwrap_unionall(d.type))
        if supertype(d.type) !== Any
            print(io, " <: ")
            b = Base.unwrap_unionall(supertype(d.type))
            Base.show_type_name(io, b.name)
            isempty(b.parameters) || print(io, "{")
            print(io, join(map(p -> p isa TypeVar ? p.name : p, b.parameters), ", "))
            isempty(b.parameters) || print(io, "}")
        end
        if isprimitivetype(d.type)
            print(io, " ", 8 * sizeof(d.type))
        end
        print(io, " end")
    end
    d.soft && print(io, "  @soft")
    kind = lowercase(string(d.tag))
    print(io, "  [", kind, " @ ", d.mod, ".", d.sym, "]")
end

Base.show(io::IO, d::AliasDiscovery) = begin
    print(io, "const ", d.mod, ".", d.sym, " = ")
    if istagtype(d.tag)
        baseShowTypeCustom(io, d.what)
    elseif istagfunction(d.tag)
        print(io, typeof(d.what).name.module, ".", nameof(d.what))
    elseif istagmodule(d.tag)
        print(io, d.what)
    end
    d.soft && print(io, "  @soft")
    kind = lowercase(string(d.tag))
    print(io, "  [", kind, " alias @ ", d.mod, ".", d.sym, "]")
end

typediscover(mods::AbstractVector{Module}=Base.loaded_modules_array();
    funcfile=nothing,
    typefile=nothing,
    aliasfile=nothing,
    skip_macros=true,
    skip_lambdas=true,
    skip_constructors=true,
    skip_callable=true,
    skip_builtins=true,
    skip_intrinsics=true,
    skip_generics=false,
    skip_closures=true,
    skip_functionsingletons=true,
    skip_types=false,
    skip_aliases=false) = begin

    filt = Set{Tag}()
    skip_macros && push!(filt, Macro)
    skip_lambdas && push!(filt, Lambda)
    skip_constructors && push!(filt, Constructor)
    skip_callable && push!(filt, Callable)
    skip_builtins && push!(filt, Builtin)
    skip_intrinsics && push!(filt, Intrinsic)
    skip_generics && push!(filt, Generic)
    skip_closures && push!(filt, Closure)
    skip_functionsingletons && push!(filt, SingletonFunctionType)
    skip_types && push!(filt, Typ)

    mods = filter(m -> m !== TypeDiscover, mods)

    funio = isnothing(funcfile) ? Base.stdout : open(funcfile, "w")
    typio = isnothing(typefile) ? Base.stdout : open(typefile, "w")
    aliasio = isnothing(aliasfile) ? Base.stdout : open(aliasfile, "w")

    try
        discover(mods) do d::Discovery
            d.tag ∈ filt && return
            d isa AliasDiscovery && skip_aliases && return

            io = if d isa FunctionDiscovery
                funio
            elseif d isa TypeDiscovery
                typio
            elseif d isa AliasDiscovery
                aliasio
            else
                throw("Unknown `Discovery` type")
            end

            println(IOContext(io, :module => nothing), d)
        end
    finally
        funio === Base.stdout || close(funio)
        typio === Base.stdout || close(typio)
        aliasio === Base.stdout || close(aliasio)
    end
end

end # module TypeDiscover
