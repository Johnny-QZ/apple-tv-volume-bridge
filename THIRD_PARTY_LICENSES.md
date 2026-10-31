# Third-party software

The project source is MIT-licensed. The shell helper includes R8-selected classes
from the following pinned dependencies; their license notices are included in
both the APK assets and helper JAR. Dependency JARs are downloaded by the user,
verified with SHA-256, and excluded from Git.

| Component | Version / coordinate | Use | License |
| --- | --- | --- | --- |
| Bouncy Castle | `org.bouncycastle:bcprov-jdk18on:1.80` | Ed25519, X25519, HKDF and ChaCha20-Poly1305 for Apple TV sessions | Bouncy Castle license (MIT-style), reproduced below |
| dd-plist | `com.googlecode.plist:dd-plist:1.28` | Binary property-list encoding/decoding | MIT, reproduced below |
| pyatv | `0.18.0` | Optional computer-side user pairing; not bundled in the APK | MIT, reproduced below |

Bouncy Castle's original Java 8 class bytes are retained. The preparation script
removes multi-release classes, signatures, module metadata and non-class resources
before R8 trims unreachable code. This derived input is not an upstream Bouncy
Castle Android distribution. No upstream binary is committed.

Sources: [Bouncy Castle 1.80 license](https://github.com/bcgit/bc-java/blob/r1rv80/LICENSE.html),
[dd-plist license](https://github.com/3breadt/dd-plist/blob/master/LICENSE.txt),
[dd-plist 1.28 Maven metadata](https://repo.maven.apache.org/maven2/com/googlecode/plist/dd-plist/1.28/dd-plist-1.28.pom),
[pyatv 0.18.0](https://github.com/postlund/pyatv/tree/v0.18.0).
The `1.28` POM embedded in the verified dd-plist JAR also declares MIT.
Android SDK/JDK build tools and pyatv's separately installed dependencies retain
their own licenses and are not redistributed in this source repository.

## Bouncy Castle 1.80

Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software
and associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.

## dd-plist 1.28

dd-plist - An open source library to parse and generate property lists

Copyright (C) 2016 Daniel Dreibrodt

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## pyatv 0.18.0 (optional pairing tool)

Copyright (c) 2020 Pierre Ståhl

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
