#!/bin/bash
# Generates Debian source and binary packages of android sysprop tool.

if [ -z "$1" ]; then
        echo "Usage: gen-src-pkg.sh <output-dir>"
        exit 1
fi

outdir="$1"
pkgdir=sysprop-0.0.1
origtar=sysprop_0.0.1.orig.tar.gz
scriptdir="$( cd "$( dirname "$0" )" && pwd )"
branch=platform-tools-34.0.0

tmpdir=$(mktemp -d)
echo Generating source package in "${tmpdir}".

cd "${tmpdir}"
# Download libbase source.
git clone --branch "${branch}" https://android.googlesource.com/platform/system/libbase || exit 1

# Download sysprop source.
git clone --branch "${branch}" https://android.googlesource.com/platform/system/tools/sysprop "${pkgdir}" || exit 1
cd "${pkgdir}"
rm -rf .git

cp -ra ../libbase/include/android-base include
echo "#include <iostream>" > include/android-base/logging.h
echo "#define LOG(x) std::cerr" >> include/android-base/logging.h
echo "#define PLOG(x) std::cerr" >> include/android-base/logging.h
cp -ra ../libbase/{file,strings,stringprintf,posix_strerror_r}.cpp .

cd ..

# Debian requires creating .orig.tar.gz.
tar czf "${origtar}" "${pkgdir}"

# Debianize the source.
cd "${pkgdir}"
yes | debmake || exit 1
cp -aT "${scriptdir}/debian/" "${tmpdir}/${pkgdir}/debian/"

# Build source package and binary package.
cd "${tmpdir}/${pkgdir}"
dpkg-buildpackage --no-sign || exit 1

# Copy the results to output dir.
cd "${tmpdir}"
mkdir -p "${outdir}/src"
cp *.dsc *.orig.tar.gz *.debian.tar.xz "${outdir}/src"
cp *.deb "${outdir}"
cd /

echo Removing temporary directory "${tmpdir}".
rm -rf "${tmpdir}"

echo Done. Check out Debian source package in "${outdir}".
