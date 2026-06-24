# Ray Setup for DUUIRayDriver

This guide covers installing [Ray](https://www.ray.io/) and setting up a Python virtual environment for components run through `DUUIRayDriver`. Unlike the other DUUI drivers, `DUUIRayDriver` does not run a pre-built container; it starts a Ray cluster as local OS processes (`ray start --head` + N workers), submits a Python entrypoint as a Ray job, and stops the cluster again on shutdown.

# TLDR
- **Ray under windws is in beta**: Some things like clusters might not work. Check their [website](https://docs.ray.io/en/latest/index.html) for the latest information 
- **Install a venv with uv**: `uv venv .venv --python 3.12` then `uv pip install "ray[default]"` (or `uv sync` against a `pyproject.toml` that pins `ray`).
- **Point the driver at the venv**: pass the venv's `ray` binary via `.withRayExecutable(...)` (per component) or `.withRaySource(...)` (driver-wide), and the venv's `python` via `.withPythonExecutable(...)`.
- **Test the cluster manually first**: `ray start --head --port=6379 --dashboard-host=0.0.0.0 --dashboard-port=8265`, check `ray status`, then `ray stop`.
- **Dashboard**: `http://<head-host>:8265` shows running jobs, logs and cluster resources; use it to debug a stuck or failed job.



# Installing Ray with uv

[uv](https://docs.astral.sh/uv/) is a fast Python package and venv manager; it replaces `python -m venv` + `pip` with a single tool and is what the `python/` examples in this repo use (see `python/pyproject.toml` and `python/uv.lock`)

## Install uv
```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
```

## Create a project venv
```bash
cd /path/to/your/ray_component
uv venv .venv --python 3.12
source .venv/bin/activate
```

## Install Ray and the component's dependencies
Either install packages directly:
```bash
uv pip install "ray[default]" fastapi uvicorn dkpro-cassis
```
or, if the component ships a `pyproject.toml` (recommended, see `python/pyproject.toml` for a working example that pins `ray[data,serve,train,tune]`, `fastapi`, `uvicorn` and friends):
```bash
uv sync
```

`uv sync` reads `pyproject.toml` (and `uv.lock` if present) and recreates `.venv` with exactly those versions, this is what you want when rebuilding the environment after pulling new code or changing dependencies

## Run things without activating the venv
`uv run` executes a single command inside the venv without needing `source .venv/bin/activate` first, useful for quick checks:
```bash
uv run ray --version
uv run python server.py
```



# Pointing DUUIRayDriver at the venv

The driver needs to know which `ray` and `python` executables to use. If they are not on the system `PATH` (true for any uv-managed venv unless activated in the same shell that starts the JVM), pass their absolute paths explicitly.

Per component:
```java
new DUUIRayDriver.Component("/path/to/ray_component", "server.py")
    .withRayExecutable("/path/to/ray_component/.venv/bin/ray")
    .withPythonExecutable("/path/to/ray_component/.venv/bin/python")
    .build()
```

Or once for the whole driver (wins over the per-component setting, useful when every component shares one venv):
```java
new DUUIRayDriver()
    .withRaySource("/path/to/ray_component/.venv/bin/ray")
```

On Windows the executables live under `.venv\Scripts\ray.exe` and `.venv\Scripts\python.exe` instead

The head node, every worker and the submitted job all run with whatever `ray` binary you point at, so keep the same venv (and therefore the same Ray version) for all of them; mixing Ray versions between head and workers leads to cryptic connection errors



# Manually exercising a cluster

Before wiring a component into DUUI it helps to start and inspect a cluster by hand, using the same venv the driver will use

```bash
source .venv/bin/activate

# Start a head node (same flags DUUIRayDriver uses by default)
ray start --head --port=6379 --dashboard-host=0.0.0.0 --dashboard-port=8265 --disable-usage-stats

# Add a worker, pointing it at the head node's address
ray start --address=localhost:6379 --num-cpus=2

# Inspect the cluster
ray status

# Submit a job the same way DUUIRayDriver does
ray job submit --address http://localhost:8265 --no-wait -- python /path/to/server.py

# List / stop jobs
ray job list --address http://localhost:8265
ray job stop <job-id> --address http://localhost:8265

# Tear down the cluster
ray stop
```

The dashboard at `http://localhost:8265` shows job status, logs and cluster resources, it is the fastest way to see why a job is stuck or failed



# Using an existing/external cluster

If a Ray cluster is already running elsewhere (e.g. on a shared GPU machine), skip starting your own and submit jobs to it instead:
```java
new DUUIRayDriver.Component("/path/to/ray_component", "server.py")
    .withClusterUrl("http://10.0.0.1:8265")
    .withTaskUrl("http://10.0.0.1:25590")
    .build()
```
`withNumWorkers(...)` has no effect in this mode (the existing cluster's topology is used as-is), and the driver never stops an external cluster on shutdown, regardless of `withKeepAlive(...)`
