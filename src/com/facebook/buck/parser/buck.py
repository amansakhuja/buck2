from __future__ import with_statement

import __future__
import copy
import fnmatch
import functools
import glob as glob_module
import imp
import inspect
import json
import optparse
import os
import os.path
import re
import sys


# When build files are executed, the functions in this file tagged with
# @provide_for_build will be provided in the build file's local symbol table.
#
# When these functions are called from a build file, they will be passed
# a keyword parameter, build_env, which is a object with information about
# the environment of the build file which is currently being processed.
# It contains the following attributes:
#
# "dirname" - The directory containing the build file.
#
# "base_path" - The base path of the build file.

BUILD_FUNCTIONS = []
BUILD_RULES_FILE_NAME = 'BUCK'


class BuildContextType(object):
    """
    Identifies the type of input file to the processor.
    """

    BUILD_FILE = 'build_file'
    INCLUDE = 'include'


class BuildFileContext(object):
    """
    The build context used when processing a build file.
    """

    type = BuildContextType.BUILD_FILE

    def __init__(self, base_path, dirname):
        self.globals = {}
        self.includes = set()
        self.base_path = base_path
        self.dirname = dirname
        self.rules = {}


class IncludeContext(object):
    """
    The build context used when processing an include.
    """

    type = BuildContextType.INCLUDE

    def __init__(self, base_path, dirname):
        self.globals = {}
        self.includes = set()
        self.base_path = base_path
        self.dirname = dirname


class LazyBuildEnvPartial(object):
    """Pairs a function with a build environment in which it will be executed.

    Note that while the function is specified via the constructor, the build
    environment must be assigned after construction, for the build environment
    currently being used.

    To call the function with its build environment, use the invoke() method of
    this class, which will forward the arguments from invoke() to the
    underlying function.
    """

    def __init__(self, func):
        self.func = func
        self.build_env = None

    def invoke(self, *args, **kwargs):
        """Invokes the bound function injecting 'build_env' into **kwargs."""
        updated_kwargs = kwargs.copy()
        updated_kwargs.update({'build_env': self.build_env})
        return self.func(*args, **updated_kwargs)


def provide_for_build(func):
    BUILD_FUNCTIONS.append(func)
    return func


def add_rule(rule, build_env):
    assert build_env.type == BuildContextType.BUILD_FILE, (
        "Cannot use `{}()` at the top-level of an included file."
        .format(rule['type']))

    # Include the base path of the BUILD file so the reader consuming this
    # JSON will know which BUILD file the rule came from.
    if 'name' not in rule:
        raise ValueError(
            'rules must contain the field \'name\'.  Found %s.' % rule)
    rule_name = rule['name']
    if rule_name in build_env.rules:
        raise ValueError('Duplicate rule definition found.  Found %s and %s' %
                         (rule, build_env.rules[rule_name]))
    rule['buck.base_path'] = build_env.base_path
    build_env.rules[rule_name] = rule


def symlink_aware_walk(base):
    """ Recursive symlink aware version of `os.walk`.

    Will not traverse a symlink that refers to a previously visited ancestor of
    the current directory.
    """
    visited_dirs = set()
    for entry in os.walk(base, topdown=True, followlinks=True):
        (root, dirs, _files) = entry
        realdirpath = os.path.realpath(root)
        if realdirpath in visited_dirs:
            absdirpath = os.path.abspath(root)
            if absdirpath.startswith(realdirpath):
                dirs[:] = []
                continue
        visited_dirs.add(realdirpath)
        yield entry
    raise StopIteration


def split_path(path):
    """Splits /foo/bar/baz.java into ['', 'foo', 'bar', 'baz.java']."""
    return path.split('/')


def well_formed_tokens(tokens):
    """Verify that a tokenized path contains no empty token."""
    return all(tokens)


def path_join(path, element):
    """Add a new path element to a path.

    This method assumes None encodes the empty path.
    """
    if path is None:
        return element
    return path + os.path.sep + element


def glob_walk_internal(
        normpath_join, iglob, isresult, visited, tokens, path, normpath):
    """Recursive routine for glob_walk.

    'visited' is initially the empty set.
    'tokens' is the list of glob elements yet to be traversed, e.g.
        ['**', '*.java'].
    'path', initially None, is the path being constructed.
    'normpath', initially os.path.realpath(root), is the os.path.realpath()-
        normalization of the path being constructed.
    'normpath_join(normpath, element)' is the os.path.realpath()-normalization
        of path_join(normpath, element)
    'iglob(pattern)' should behave like glob.iglob if 'path' were relative to
        the current directory
    'isresult(path)' should verify that path is valid as a result (typically
        calls os.path.isfile)
    """
    # Base case.
    if not tokens:
        if isresult(path):
            yield path
        return

    token = tokens[0]
    next_tokens = tokens[1:]

    # Special base case of ['**'].
    if token == '**' and not next_tokens:
        if isresult(path):
            yield path
        # Continue for the non-base case.

    # Except for the base cases above, refuse to visit twice the same
    # normalized path with the same tokens.
    # This is necessary for termination in case of symlinks.
    key = (tuple(tokens), normpath)
    if key in visited:
        return
    visited.add(key)

    path_and_sep_len = len(path) + 1 if path is not None else 0

    # Special glob token, equivalent to zero or more consecutive '*'
    if token == '**':
        # The base case of ['**'] was handled above.
        if next_tokens:
            for x in glob_walk_internal(
                    normpath_join, iglob, isresult,
                    visited, next_tokens, path,
                    normpath):
                yield x
        for child in iglob(path_join(path, '*')):
            for x in glob_walk_internal(
                    normpath_join, iglob, isresult,
                    visited, tokens, child,
                    normpath_join(normpath, child[path_and_sep_len:])):
                yield x

    # Usual glob pattern (normal case).
    elif next_tokens:
        for child in iglob(path_join(path, token)):
            for x in glob_walk_internal(
                    normpath_join, iglob, isresult,
                    visited, next_tokens, child,
                    normpath_join(normpath, child[path_and_sep_len:])):
                yield x
    # Usual glob pattern (optimized when there are no next tokens).
    else:
        for child in iglob(path_join(path, token)):
            for x in glob_walk_internal(
                    normpath_join, iglob, isresult,
                    visited, None, child, None):
                yield x


def glob_walk(pattern, root, include_dotfiles=False):
    """Walk the path hierarchy, following symlinks, and emit relative paths to
    plain files matching 'pattern'.

    Patterns can be any combination of chars recognized by shell globs.
    The special path token '**' expands to zero or more consecutive '*'.
    E.g. '**/foo.java' will match the union of 'foo.java', '*/foo.java.',
    '*/*/foo.java', etc.

    Names starting with dots will not be matched by '?', '*' and '**' unless
    include_dotfiles=True
    """
    # os.path.realpath()-normalized version of path_join
    def normpath_join(normpath, element):
        newpath = normpath + os.path.sep + element
        if os.path.islink(newpath):
            return os.path.realpath(newpath)
        else:
            return newpath

    # Relativized version of glob.iglob
    # Note that glob.iglob already optimizes paths with no special char.
    root_len = len(os.path.join(root, ''))
    special_rules_for_dots = (
        ((r'^\*', '.*'), (r'^\?', '.'), (r'/\*', '/.*'), (r'/\?', '/.'))
        if include_dotfiles else [])

    def iglob(pattern):
        for p in glob_module.iglob(os.path.join(root, pattern)):
            yield p[root_len:]
        # Additional pass for dots.
        # Note that there is at most one occurrence of one problematic pattern.
        for rule in special_rules_for_dots:
            special = re.sub(rule[0], rule[1], pattern, count=1)
            # Using pointer equality for speed:
            # http://docs.python.org/2.7/library/re.html#re.sub
            if special is not pattern:
                for p in glob_module.iglob(os.path.join(root, special)):
                    yield p[root_len:]
                break

    # Relativized version of os.path.isfile
    def isresult(path):
        if path is None:
            return False
        return os.path.isfile(os.path.join(root, path))

    visited = set()
    tokens = split_path(pattern)
    assert well_formed_tokens(tokens), (
        "Glob patterns cannot be empty, start or end with a slash, or contain "
        "consecutive slashes.")
    return glob_walk_internal(
        normpath_join, iglob, isresult, visited, tokens, None,
        os.path.realpath(root))


def glob_match_internal(include_dotfiles, tokens, chunks):
    """Recursive routine for glob_match.

    Works as glob_walk_internal but on a linear path instead of some
    filesystem.
    """
    # Base case(s).
    if not tokens:
        return True if not chunks else False
    token = tokens[0]
    next_tokens = tokens[1:]
    if not chunks:
        return (glob_match_internal(include_dotfiles, next_tokens, chunks)
                if token == '**' else False)
    chunk = chunks[0]
    next_chunks = chunks[1:]

    # Plain name (possibly empty)
    if not glob_module.has_magic(token):
        return (
            token == chunk and
            glob_match_internal(include_dotfiles, next_tokens, next_chunks))

    # Special glob token.
    elif token == '**':
        if glob_match_internal(include_dotfiles, next_tokens, chunks):
            return True
        # Simulate glob pattern '*'
        if not include_dotfiles and chunk and chunk[0] == '.':
            return False
        return glob_match_internal(include_dotfiles, tokens, next_chunks)

    # Usual glob pattern.
    else:
        # We use the same internal library fnmatch as the original code:
        #    http://hg.python.org/cpython/file/2.7/Lib/glob.py#l76
        # TODO(user): to match glob.glob, '.*' should not match '.'
        # or '..'
        if (not include_dotfiles and
                token[0] != '.' and
                chunk and
                chunk[0] == '.'):
            return False
        return (
            fnmatch.fnmatch(chunk, token) and
            glob_match_internal(include_dotfiles, next_tokens, next_chunks))


def glob_match(pattern, path, include_dotfiles=False):
    """Checks if a given (non necessarily existing) path matches a 'pattern'.

    Patterns can include the same special tokens as glob_walk.
    Paths and patterns are seen as a list of path elements delimited by '/'.
    E.g. '/' does not match '', but '*' does.
    """
    tokens = split_path(pattern)
    chunks = split_path(path)
    return glob_match_internal(include_dotfiles, tokens, chunks)


@provide_for_build
def glob(includes, excludes=[], include_dotfiles=False, build_env=None):
    assert build_env.type == BuildContextType.BUILD_FILE, (
        "Cannot use `glob()` at the top-level of an included file.")

    search_base = build_env.dirname

    # Ensure the user passes lists of strings rather than just a string.
    assert not isinstance(includes, basestring), \
        "The first argument to glob() must be a list of strings."
    assert not isinstance(excludes, basestring), \
        "The excludes argument must be a list of strings."

    paths = set()
    for pattern in includes:
        for path in glob_walk(
                pattern, search_base, include_dotfiles=include_dotfiles):
            paths.add(path)

    def exclusion(path):
        exclusions = (
            e for e in excludes
            if glob_match(e, path, include_dotfiles=include_dotfiles))
        return next(exclusions, None)

    paths = [p for p in paths if not exclusion(p)]
    paths.sort()
    return paths


@provide_for_build
def get_base_path(build_env=None):
    """Get the base path to the build file that was initially evaluated.

    This function is intended to be used from within a build defs file that
    likely contains macros that could be called from any build file.
    Such macros may need to know the base path of the file in which they
    are defining new build rules.

    Returns: a string, such as "java/com/facebook". Note there is no
             trailing slash. The return value will be "" if called from
             the build file in the root of the project.
    """
    return build_env.base_path

@provide_for_build
def get_dir_name(build_env=None):
    """Get the directory name of the build or include file.

    This function can be called in both build file and include file contexts.

    Returns: directory name.
    """
    return build_env.dirname

@provide_for_build
def add_deps(name, deps=[], build_env=None):
    assert build_env.type == BuildContextType.BUILD_FILE, (
        "Cannot use `add_deps()` at the top-level of an included file.")

    if name not in build_env.rules:
        raise ValueError(
            'Invoked \'add_deps\' on non-existent rule %s.' % name)

    rule = build_env.rules[name]
    if 'deps' not in rule:
        raise ValueError(
            'Invoked \'add_deps\' on rule %s that has no \'deps\' field'
            % name)
    rule['deps'] = rule['deps'] + deps


class BuildFileProcessor(object):

    def __init__(self, project_root, implicit_includes=[]):
        self._cache = {}
        self._build_env_stack = []

        self._project_root = project_root
        self._implicit_includes = implicit_includes

        lazy_functions = {}
        for func in BUILD_FUNCTIONS:
            func_with_env = LazyBuildEnvPartial(func)
            lazy_functions[func.__name__] = func_with_env
        self._functions = lazy_functions

    def _merge_globals(self, src, dst):
        """
        Copy the global definitions from one globals dict to another.

        Ignores special attributes and attributes starting with '_', which
        typically denote module-level private attributes.
        """

        hidden = set([
            'include_defs',
        ])

        for key, val in src.iteritems():
            if not key.startswith('_') and key not in hidden:
                dst[key] = val

    def _update_functions(self, build_env):
        """
        Updates the build functions to use the given build context when called.
        """

        for function in self._functions.itervalues():
            function.build_env = build_env

    def _install_functions(self, namespace):
        """
        Installs the build functions, by their name, into the given namespace.
        """

        for name, function in self._functions.iteritems():
            namespace[name] = function.invoke

    def _get_include_path(self, name):
        """
        Resolve the given include def name to a full path.
        """

        # Find the path from the include def name.
        if not name.startswith('//'):
            raise ValueError(
                'include_defs argument "%s" must begin with //' % name)
        relative_path = name[2:]
        return os.path.join(self._project_root, name[2:])

    def _include_defs(self, name, implicit_includes=[]):
        """
        Pull the named include into the current caller's context.

        This method is meant to be installed into the globals of any files or
        includes that we process.
        """

        # Grab the current build context from the top of the stack.
        build_env = self._build_env_stack[-1]

        # Resolve the named include to its path and process it to get its
        # build context and module.
        path = self._get_include_path(name)
        inner_env, mod = self._process_include(
            path,
            implicit_includes=implicit_includes)

        # Look up the caller's stack frame and merge the include's globals
        # into it's symbol table.
        frame = inspect.currentframe()
        while frame.f_globals['__name__'] == __name__:
            frame = frame.f_back
        self._merge_globals(mod.__dict__, frame.f_globals)

        # Pull in the include's accounting of its own referenced includes
        # into the current build context.
        build_env.includes.add(path)
        build_env.includes.update(inner_env.includes)

    def _push_build_env(self, build_env):
        """
        Set the given build context as the current context.
        """

        self._build_env_stack.append(build_env)
        self._update_functions(build_env)

    def _pop_build_env(self):
        """
        Restore the previous build context as the current context.
        """

        self._build_env_stack.pop()
        if self._build_env_stack:
            self._update_functions(self._build_env_stack[-1])

    def _process(self, build_env, path, implicit_includes=[]):
        """
        Process a build file or include at the given path.
        """

        # First check the cache.
        cached = self._cache.get(path)
        if cached is not None:
            return cached

        # Install the build context for this input as the current context.
        self._push_build_env(build_env)

        # The globals dict that this file will be executed under.
        default_globals = {}

        # Install the implicit build functions and adding the 'include_defs'
        # functions.
        self._install_functions(default_globals)
        default_globals['include_defs'] = functools.partial(
            self._include_defs,
            implicit_includes=implicit_includes)

        # If any implicit includes were specified, process them first.
        for include in implicit_includes:
            include_path = self._get_include_path(include)
            inner_env, mod = self._process_include(include_path)
            self._merge_globals(mod.__dict__, default_globals)
            build_env.includes.add(include_path)
            build_env.includes.update(inner_env.includes)

        # Build a new module for the given file, using the default globals
        # created above.
        module = imp.new_module(path)
        module.__file__ = path
        module.__dict__.update(default_globals)

        with open(path) as f:
            contents = f.read()

        # Enable absolute imports.  This prevents the compiler from trying to
        # do a relative import first, and warning that this module doesn't
        # exist in sys.modules.
        future_features = __future__.absolute_import.compiler_flag
        code = compile(contents, path, 'exec', future_features, 1)
        exec(code, module.__dict__)

        # Restore the previous build context.
        self._pop_build_env()

        self._cache[path] = build_env, module
        return build_env, module

    def _process_include(self, path, implicit_includes=[]):
        """
        Process the include file at the given path.
        """

        base_path = os.path.relpath(
            path, self._project_root).replace('\\', '/')
        dirname = os.path.dirname(path)
        build_env = IncludeContext(base_path, dirname)
        return self._process(
            build_env,
            path,
            implicit_includes=implicit_includes)

    def _process_build_file(self, path, implicit_includes=[]):
        """
        Process the build file at the given path.
        """

        # Create the build file context, including the base path and directory
        # name of the given path.
        relative_path_to_build_file = os.path.relpath(
            path, self._project_root).replace('\\', '/')
        len_suffix = -len('/' + BUILD_RULES_FILE_NAME)
        base_path = relative_path_to_build_file[:len_suffix]
        dirname = os.path.dirname(path)
        build_env = BuildFileContext(base_path, dirname)

        return self._process(
            build_env,
            path,
            implicit_includes=implicit_includes)

    def process(self, path):
        """
        Process a build file returning a dict of it's rules and includes.
        """

        build_env, mod = self._process_build_file(
            os.path.join(self._project_root, path),
            implicit_includes=self._implicit_includes)
        values = build_env.rules.values()
        values.append({"__includes": [path] + sorted(build_env.includes)})
        return values


# Inexplicably, this script appears to run faster when the arguments passed
# into it are absolute paths. However, we want the "buck.base_path" property
# of each rule to be printed out to be the base path of the build target that
# identifies the rule. That means that when parsing a BUILD file, we must know
# its path relative to the root of the project to produce the base path.
#
# To that end, the first argument to this script must be an absolute path to
# the project root.  It must be followed by one or more absolute paths to
# BUILD files under the project root.  If no paths to BUILD files are
# specified, then it will traverse the project root for BUILD files, excluding
# directories of generated files produced by Buck.
#
# All of the build rules that are parsed from the BUILD files will be printed
# to stdout by a JSON parser. That means that printing out other information
# for debugging purposes will likely break the JSON parsing, so be careful!


def main():
    parser = optparse.OptionParser()
    parser.add_option(
        '--project_root',
        action='store',
        type='string',
        dest='project_root')
    parser.add_option(
        '--include',
        action='append',
        dest='include')
    parser.add_option(
        '--ignore_path',
        action='append',
        dest='ignore_paths')
    parser.add_option(
        '--server',
        action='store_true',
        dest='server',
        help='Invoke as a server to parse individual BUCK files on demand.')
    (options, args) = parser.parse_args()

    # Even though project_root is absolute path, it may not be concise. For
    # example, it might be like "C:\project\.\rule".  We normalize it in order
    # to make it consistent with ignore_paths.
    project_root = os.path.abspath(options.project_root)

    build_files = []
    if args:
        # The user has specified which build files to parse.
        build_files = args
    elif not options.server:
        # Find all of the build files in the project root. Symlinks will not be
        # traversed. Search must be done top-down so that directory filtering
        # works as desired. options.ignore_paths may contain /, which is needed
        # to be normalized in order to do string pattern matching.
        ignore_paths = [
            os.path.abspath(os.path.join(project_root, d))
            for d in options.ignore_paths or []]
        build_files = []
        for dirpath, dirnames, filenames in symlink_aware_walk(project_root):
            # Do not walk directories that contain generated/non-source files.
            # All modifications to dirnames must occur in-place.
            dirnames[:] = [
                d for d in dirnames
                if not (os.path.join(dirpath, d) in ignore_paths)]

            if BUILD_RULES_FILE_NAME in filenames:
                build_file = os.path.join(dirpath, BUILD_RULES_FILE_NAME)
                build_files.append(build_file)

    buildFileProcessor = BuildFileProcessor(
        project_root,
        implicit_includes=options.include or [])

    for build_file in build_files:
        values = buildFileProcessor.process(build_file)
        if options.server:
            print json.dumps(values)
        else:
            for value in values:
                print json.dumps(value)

    if options.server:
        # "for ... in sys.stdin" in Python 2.x hangs until stdin is closed.
        for build_file in iter(sys.stdin.readline, ''):
            values = buildFileProcessor.process(build_file.rstrip())
            print json.dumps(values)

    # Python tries to flush/close stdout when it quits, and if there's a dead
    # pipe on the other end, it will spit some warnings to stderr. This breaks
    # tests sometimes. Prevent that by explicitly catching the error.
    try:
        sys.stdout.close()
    except IOError:
        pass
