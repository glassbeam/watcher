watcher
=======

File watcher library based on inotify

BUILDING
--

    REQUIREMENTS
        * A Java SDK in JAVA_HOME
        * Ant

    CONFIGURATION
        Run ./configure, and optionally specify a prefix and libdir to use
        during installation. For example,

            ./configure --prefix=/usr --libdir=/usr/lib64

        would result in the native library being installed in /usr/lib64, while
        the Jar file would reside in /usr/share/inotify-java/lib.

    Type 'make'.

    Assuming a successful build, the Jar file will be in src/java.  The native
    library will be in src/cpp.


INSTALLING
--
    Type 'make install'.


UNINSTALLING
--
    Likewise, 'make uninstall' is also supported.


SOURCE TREE
--

    CONVENTIONS
        Contains the conventions used throughout the inotify-java source code.

    COPYING:
        The full text of the GNU General Public License Version 3.

    COPYING.LESSER:
        The full text of the GNU Lesser General Public License Version 3.

    LICENSE:
        The inotify-java license (LGPLv3).

    ChangeLog:
        Version control changelog.

    README:
        This file.

    AUTHORS:
        List of people who actively work on the library.

    CREDITS:
        List of people who have contributed to the library.

    TODO:
        List of outstanding tasks.
