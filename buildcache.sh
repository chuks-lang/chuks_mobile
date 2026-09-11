# Locating and clearing the Chuks build cache, shared by both host builds.
#
# `chuks build --c-archive` writes its generated Go into ~/.chuks/cache/builds/<hash>/,
# and the host build then compiles that directory. The cache GROWS without bound: this
# is written after finding 74,473 directories and 3.9 GB on one machine.
#
# At that size a shell glob over the cache exceeds ARG_MAX and every use of one fails:
#
#   ls -dt "$HOME"/.chuks/cache/builds/*/ | head -1   -> "argument list too long"
#   rm -rf ~/.chuks/cache/builds/*                    -> the same, and under `set -e`
#                                                        it aborts the build
#
# The lookup failure was the worse of the two because it was SILENT: the result was
# empty, `cd ""` stayed in the current directory, and the build failed with
# "go: cannot find main module" naming the project root, which points at everything
# except the cache. Use these helpers instead of a glob.
CHUKS_BUILD_CACHE="$HOME/.chuks/cache/builds"

# The directory `chuks build` just generated into.
# $1 is a file created immediately BEFORE that build ran: only directories newer than
# it are considered, so a concurrent build of another project cannot be picked up.
chuks_latest_build_dir() {
    find "$CHUKS_BUILD_CACHE" -mindepth 1 -maxdepth 1 -type d -newer "$1" \
        -exec stat -f '%m %N' {} + 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-
}

# Empty the cache without expanding it onto the command line.
chuks_clear_build_cache() {
    [ -d "$CHUKS_BUILD_CACHE" ] || return 0
    find "$CHUKS_BUILD_CACHE" -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || true
}
