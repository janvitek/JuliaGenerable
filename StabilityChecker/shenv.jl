pkgs = isempty(ARGS) ? error("Required argument: path of pkgs.txt") : joinpath(pwd(), popfirst!(ARGS))

const env = "JuliaGenerable"

using Pkg

Pkg.activate(env; shared=true)
Pkg.add(split(strip(read(pkgs, String)), "\n"))
Pkg.instantiate()

@info "Shared environment `@$env' at `$(joinpath(Pkg.envdir(), env))':"
Pkg.status()
