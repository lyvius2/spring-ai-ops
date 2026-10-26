#!/bin/sh
set -eu

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "Usage: $0 VERSION [linux/amd64|linux/arm64|linux/amd64,linux/arm64]" >&2
    exit 2
fi
repository=lyvius2/spring-ai-ops
version=$1
platform=${2:-linux/amd64}
case "$version" in
    ''|[.-]*|*[!a-zA-Z0-9_.-]*) echo "Invalid image tag." >&2; exit 2 ;;
esac
case "$platform" in
    linux/amd64|linux/arm64|linux/amd64,linux/arm64) ;;
    *) echo "Unsupported platform: $platform" >&2; exit 2 ;;
esac

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
image="docker.io/$repository:$version"
command -v docker >/dev/null 2>&1 || { echo "Docker is required." >&2; exit 1; }
docker buildx version >/dev/null
docker info >/dev/null

# Run docker login beforehand; credentials never enter the build.
# Dockerfile invokes nativeCompile only and copies only production sources.
echo "Building and publishing $image for $platform (tests disabled)"
docker buildx build \
    --file "$project_dir/Dockerfile" \
    --platform "$platform" \
    --build-arg "NATIVE_IMAGE_HEAP=${NATIVE_IMAGE_HEAP:-8g}" \
    --tag "$image" \
    --push \
    "$project_dir"
echo "Published $image"
