#!/bin/bash -eu
# Copyright 2016 Google Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
################################################################################

# Disable logging via library build configuration control.
cat scripts/pnglibconf.dfa | sed -e "s/option STDIO/option STDIO disabled/" \
> scripts/pnglibconf.dfa.temp
mv scripts/pnglibconf.dfa.temp scripts/pnglibconf.dfa

# build the library.
autoreconf -f -i
./configure
make -j$(nproc) clean
make -j$(nproc) all

# build libpng_read_fuzzer
$CXX $CXXFLAGS -std=c++11 -I. -lz \
     $SRC/libpng_read_fuzzer.cc -o $OUT/libpng_read_fuzzer \
     -lFuzzingEngine .libs/libpng16.a

cp $SRC/*.dict $SRC/*.options $OUT/
