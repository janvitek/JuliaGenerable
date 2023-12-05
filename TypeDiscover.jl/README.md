# TypeDiscover.jl

Write types and function signatures discovered in given modules to files.

To get started,

```bash
$ julia --project=./TypeDiscover.jl
```

Then,

```julia
using TypeDiscover

# Here, load all packages of interest
using CSV

# By default, process all loaded modules (except TypeDiscover itself)
typediscover(; funcfile="funcs.jlg", typefile="types.jlg")

# Or, only listed modules
typediscover([CSV]; funcfile="csv_funcs.jlg", typefile="csv_types.jlg")
```

Full signature:

```julia
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
    skip_aliases=true,
    skip_closures=true,
    skip_functiontypes=true,
    skip_types=false)
```

Leaving files as `nothing` causes the output to be written to starndard output. Changing any of the flags hides/shows that particular kind of type/signature.
