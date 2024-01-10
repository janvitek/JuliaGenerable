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
    Alias
    Closure
    FunctionType
    Typ
end

abstract type Discovery end

struct FunctionDiscovery <: Discovery
    tag::Tag
    fun::Function
    meth::Method
end

struct TypeDiscovery <: Discovery
    tag::Tag
    mod::Module
    sym::Symbol
    type::Type
end

tagof(f::Function)::Tag = begin
    f isa Core.Builtin && return Builtin
    f isa Core.IntrinsicFunction && return Intrinsic
    mt = typeof(f).name.mt
    name = mt.name
    hasname = isdefined(mt.module, name) && typeof(getfield(mt.module, name)) <: Function
    sname = string(name)
    if hasname
        if startswith(sname, '@')
            return Macro
        else
            return Generic
        end
    elseif '#' in sname
        return Lambda
    elseif mt === Base._TYPE_NAME.mt
        return Constructor
    else
        return Callable
    end
end

tagof(m::Module, s::Symbol, t::Type)::Tag = begin
    base = Base.unwrap_unionall(t)
    if t <: Function
        if occursin("var\"#", string(base))
            return Closure
        else
            return FunctionType
        end
    elseif base isa Union || t !== base.name.wrapper || m !== base.name.module || string(s) != string(nameof(t))
        return Alias
    else
        return Typ
    end
end

ismodulenested(m::Module, outer::Module)::Bool = begin
    sentinel = Base.moduleroot(m)
    while true
        m === outer && return true
        m === sentinel && return false
        m = parentmodule(m)
    end
end

struct OpaqueDiscovery
    x::Union{Function, Tuple{Module, Symbol, Type}}
end

discover(report::Function, modules::Vector{Module}) = begin
    visited = Set{Module}()
    discovered = Set{OpaqueDiscovery}([
        OpaqueDiscovery((Core, :Union, Union)),
        OpaqueDiscovery((Core, :UnionAll, UnionAll)),
        OpaqueDiscovery((Core, :DataType, DataType)),
        OpaqueDiscovery((Core, :TypeofBottom, Core.TypeofBottom))
    ])

    discoveraux(mod::Module, root::Module) = begin
        mod ∈ visited && return
        push!(visited, mod)

        for sym in names(mod; all=true, imported=true)
            try
                val = getproperty(mod, sym)

                if val isa Module && ismodulenested(val, root)
                    discoveraux(val, root)
                    continue
                end

                if val isa Function && sym ∉ [:include, :eval]
                    d = OpaqueDiscovery(val)
                    d ∈ discovered && continue
                    push!(discovered, d)
                    tag = tagof(val)
                    for m in methods(val)
                        any(mod -> ismodulenested(m.module, mod), modules) && report(FunctionDiscovery(tag, val, m))
                    end
                end

                if val isa Type
                    d = OpaqueDiscovery((mod, sym, val))
                    d ∈ discovered && continue
                    push!(discovered, d)
                    report(TypeDiscovery(tagof(mod, sym, val), mod, sym, val))
                end
            catch e
                if e isa UndefVarError
                    GlobalRef(mod, sym) ∉ [GlobalRef(Base, :active_repl), GlobalRef(Base, :active_repl_backend),
                        GlobalRef(Base.Filesystem, :JL_O_TEMPORARY), GlobalRef(Base.Filesystem, :JL_O_SHORT_LIVED),
                        GlobalRef(Base.Filesystem, :JL_O_SEQUENTIAL), GlobalRef(Base.Filesystem, :JL_O_RANDOM)] &&
                        @warn "Module $mod exports symbol $sym but it's undefined."
                else
                    throw(e)
                end
            end
        end
    end

    for m in modules
        discoveraux(m, m)
    end
end

module TestMod

# import SentinelArrays
# const SVec{T} = SentinelArrays.SentinelVector{T, T, Missing, Vector{T}}

# gener(captured) = [captured + i for i in 1:2]

# f(abc) = x -> abc + x

# const lambda = (x::Int32) -> x + 1

# const MyVector{T} = Array{T,1}
# const MyVector2 = Vector
# const MyVector3 = Vector{T} where T
# const MyVectorInt = Vector{Int}
# struct CrazyArray{A, B, C, D, E} end
# const MyCrazyArray{T, U} = CrazyArray{T, U, Int, Bool, 3}

# const my8by16 = NTuple{16, VecElement{UInt8}}
# const my8by3 = NTuple{3, VecElement{UInt8}}

# struct X{T}
#     x::T
# end
# function (x::X)(a) # X.body.name.mt has callable objects (one mt for all of them??)
#     return x.x + a
# end

struct K
    k::Int
end
K(a, b, c) = K(a + b + c)

# abstract type TestAbstract end

# struct TestStruct <: TestAbstract end

# kwargs(x::Int; kw1::String = "hi", kw2::Bool) = 1

# vargs(x::String, y::Int...) = 1

# defaultargs(x::Int, def1::String = "hey", def2::Bool = false) = 1

# testfunc(x, y::String) = 1

# import Base.Int8

# primitive type TestPrimitive 40 end

# abstract type Abs{T} end
# struct Conc{T, U <: Array{<:T}} <: Abs{T} end

# foo(::Vector{T}, ::T) where T <: Number = 1

# module Submod
# const MyVector2 = Vector

# struct Substruct
#     x::Int
# end

# subfunction(x::Int, y::Bool)::String = "$x, $y"
# import ..testfunc
# testfunc(::Bool) = 1

# end

end

baseShowMethodCustom(io::IO, m::Method, kind::String) = begin
    tv, decls, file, line = Base.arg_decl_parts(m)
    sig = Base.unwrap_unionall(m.sig)
    if sig === Tuple
        # Builtin
        print(io, m.module, ".", m.name, "(...)  [", kind, "]")
        return
    end
    print(io, m.module, ".", decls[1][2], "(")
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
    @assert Macro <= d.tag <= Generic
    print(io, "function ")
    kind = lowercase(string(d.tag))
    baseShowMethodCustom(io, d.meth, kind)
end

Base.show(io::IO, d::TypeDiscovery) = begin
    @assert Alias <= d.tag <= Typ
    if d.tag == Alias
        print(io, "const ", d.mod, ".", d.sym, " = ")
        baseShowTypeCustom(io, d.type)
    elseif d.tag == FunctionType
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
    kind = lowercase(string(d.tag))
    print(io, "  [", kind, " @ ", d.mod, ".", d.sym, "]")
end

typediscover(mods::AbstractVector{Module}=Base.loaded_modules_array();
    funcfile=nothing,
    typefile=nothing,
    skip_macros=true,
    skip_lambdas=true,
    skip_constructors=false,
    skip_callable=false,
    skip_builtins=true,
    skip_intrinsics=true,
    skip_generics=false,
    skip_aliases=false,
    skip_closures=true,
    skip_functiontypes=true,
    skip_types=false) = begin

    filt = Set{Tag}()
    skip_macros && push!(filt, Macro)
    skip_lambdas && push!(filt, Lambda)
    skip_constructors && push!(filt, Constructor)
    skip_callable && push!(filt, Callable)
    skip_builtins && push!(filt, Builtin)
    skip_intrinsics && push!(filt, Intrinsic)
    skip_generics && push!(filt, Generic)
    skip_aliases && push!(filt, Alias)
    skip_closures && push!(filt, Closure)
    skip_functiontypes && push!(filt, FunctionType)
    skip_types && push!(filt, Type)

    mods = filter(m -> m !== TypeDiscover, mods)

    funio = isnothing(funcfile) ? Base.stdout : open(funcfile, "w")
    typio = isnothing(typefile) ? Base.stdout : open(typefile, "w")

    try
        discover(mods) do d::Discovery
            d.tag ∈ filt && return
            io = d isa FunctionDiscovery ? funio : typio
            println(IOContext(io, :module => nothing), d)
        end
    finally
        funio === Base.stdout || close(funio)
        typio === Base.stdout || close(typio)
    end
end

end # module TypeDiscover
