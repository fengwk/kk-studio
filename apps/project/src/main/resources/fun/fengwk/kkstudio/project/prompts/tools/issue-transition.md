Request a controlled handoff of the current Issue stage.

Use it only when the work of the current stage is done. The target must be one of the
stage's declared next stages; the request is validated against the current stage of the
live Issue and the currently running run. The platform stores the accepted target on the
current run and applies it only when the run is safely closed out, so the Issue stage does
not change while the run is still working. After a handoff has been accepted, do not start
new business writes; finish the run with a final report of what was done and how it was
verified.
