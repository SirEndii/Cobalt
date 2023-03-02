/*
 * The MIT License (MIT)
 *
 * Original Source: Copyright (c) 2009-2011 Luaj.org. All rights reserved.
 * Modifications: Copyright (c) 2015-2020 SquidDev
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.squiddev.cobalt.lib.system;


import org.squiddev.cobalt.*;
import org.squiddev.cobalt.function.LibFunction;
import org.squiddev.cobalt.function.LuaFunction;
import org.squiddev.cobalt.function.RegisteredFunction;
import org.squiddev.cobalt.lib.BaseLib;

import static org.squiddev.cobalt.OperationHelper.noUnwind;
import static org.squiddev.cobalt.ValueFactory.*;

/**
 * Subclass of {@link LibFunction} which implements the lua standard package and module
 * library functions.
 * <p>
 * This has been implemented to match as closely as possible the behavior in the corresponding library in C.
 * However, the default filesystem search semantics are different and delegated to the bas library
 * as outlined in the {@link BaseLib}.
 *
 * @see LibFunction
 * @see BaseLib
 * @see <a href="http://www.lua.org/manual/5.1/manual.html#5.3">http://www.lua.org/manual/5.1/manual.html#5.3</a>
 */
public class PackageLib {
	private static final LuaString _M = valueOf("_M");
	private static final LuaString _NAME = valueOf("_NAME");
	private static final LuaString _PACKAGE = valueOf("_PACKAGE");
	private static final LuaString _DOT = valueOf(".");
	private static final LuaString _LOADERS = valueOf("loaders");
	private static final LuaString _LOADED = valueOf("loaded");
	private static final LuaString _LOADLIB = valueOf("loadlib");
	private static final LuaString _PRELOAD = valueOf("preload");
	private static final LuaString _PATH = valueOf("path");
	private static final LuaString _PATH_DEFAULT = valueOf("?.lua");
	private static final LuaString _CPATH = valueOf("cpath");
	private static final LuaString _CPATH_DEFAULT = Constants.EMPTYSTRING;
	private static final LuaString _SEEALL = valueOf("seeall");

	private static final LuaString REGISTRY_PRELOAD = valueOf("_PRELOAD");

	private final ResourceLoader loader;
	private final LuaValue sentinel = userdataOf(new Object());
	private LuaTable packageTbl;

	public PackageLib(ResourceLoader loader) {
		this.loader = loader;
	}

	public void add(LuaState state, LuaTable env) {
		env.rawset("require", RegisteredFunction.of("require", (s, a) -> OperationHelper.noUnwind(s, () -> require(s, a))).create());
		env.rawset("module", RegisteredFunction.ofV("require", (s, a) -> OperationHelper.noUnwind(s, () -> module(s, a))).create());

		LibFunction.setGlobalLibrary(state, env, "package", packageTbl = tableOf(
			_LOADED, loaded(state),
			_PRELOAD, state.registry().getSubTable(REGISTRY_PRELOAD),
			_PATH, _PATH_DEFAULT,
			_LOADLIB, RegisteredFunction.ofV("loadlib", PackageLib::loadlib).create(),
			_SEEALL, RegisteredFunction.ofV("seeall", PackageLib::seeall).create(),
			_CPATH, _CPATH_DEFAULT,
			_LOADERS, listOf(
				RegisteredFunction.ofV("preload_loader", (s, a) -> OperationHelper.noUnwind(s, () -> loader_preload(s, a))).create(),
				RegisteredFunction.ofV("lua_loader", (s, a) -> OperationHelper.noUnwind(s, () -> loader_Lua(s, a))).create(),
				RegisteredFunction.ofV("java_loader", (s, a) -> OperationHelper.noUnwind(s, () -> loader_Java(a))).create()
			)
		));
	}

	private static LuaTable loaded(LuaState state) {
		return state.registry().getSubTable(Constants.LOADED);
	}

	private static Varargs seeall(LuaState state, Varargs args) throws LuaError {
		LuaTable t = args.first().checkTable();
		LuaTable m = t.getMetatable(state);
		if (m == null) {
			t.setMetatable(state, m = ValueFactory.tableOf());
		}
		LuaTable mt = m;
		noUnwind(state, () -> OperationHelper.setTable(state, mt, Constants.INDEX, state.getCurrentThread().getfenv()));
		return Constants.NONE;
	}

	@Override
	public String toString() {
		return "package";
	}


	// ======================== Module, Package loading =============================

	/**
	 * module (name [, ...])
	 * <p>
	 * Creates a module. If there is a table in package.loaded[name], this table
	 * is the module. Otherwise, if there is a global table t with the given
	 * name, this table is the module. Otherwise creates a new table t and sets
	 * it as the value of the global name and the value of package.loaded[name].
	 * This function also initializes t._NAME with the given name, t._M with the
	 * module (t itself), and t._PACKAGE with the package name (the full module
	 * name minus last component; see below). Finally, module sets t as the new
	 * environment of the current function and the new value of
	 * package.loaded[name], so that require returns t.
	 * <p>
	 * If name is a compound name (that is, one with components separated by
	 * dots), module creates (or reuses, if they already exist) tables for each
	 * component. For instance, if name is a.b.c, then module stores the module
	 * table in field c of field b of global a.
	 * <p>
	 * This function may receive optional options after the module name, where
	 * each option is a function to be applied over the module.
	 *
	 * @param state The current lua state
	 * @param args  The arguments to set it up with
	 * @return {@link Constants#NONE}
	 * @throws LuaError If there is a name conflict.
	 */
	private Varargs module(LuaState state, Varargs args) throws LuaError, UnwindThrowable {
		LuaTable loaded = loaded(state);
		LuaString modname = args.arg(1).checkLuaString();
		int n = args.count();
		LuaValue value = loaded.rawget(modname);
		LuaTable module;
		if (!value.isTable()) { /* not found? */
			/* try global variable (and create one if it does not exist) */
			LuaTable globals = state.getCurrentThread().getfenv();
			module = findtable(globals, modname);
			if (module == null) {
				throw new LuaError("name conflict for module '" + modname + "'");
			}
			loaded.rawset(modname, module);
		} else {
			module = (LuaTable) value;
		}

		/* check whether table already has a _NAME field */
		LuaValue name = OperationHelper.getTable(state, module, _NAME);
		if (name.isNil()) {
			modinit(state, module, modname);
		}

		// set the environment of the current function
		LuaFunction f = LuaThread.getCallstackFunction(state, 0);
		if (f == null) {
			throw new LuaError("no calling function");
		}
		if (!f.isClosure()) {
			throw new LuaError("'module' not called from a Lua function");
		}
		f.setfenv(module);

		// apply the functions
		for (int i = 2; i <= n; i++) {
			OperationHelper.call(state, args.arg(i), module);
		}

		// returns no results
		return Constants.NONE;
	}

	/**
	 * @param table the table at which to start the search
	 * @param fname the name to look up or create, such as "abc.def.ghi"
	 * @return the table for that name, possible a new one, or null if a non-table has that name already.
	 */
	private static LuaTable findtable(LuaTable table, LuaString fname) {
		int b, e = (-1);
		do {
			e = fname.indexOf(_DOT, b = e + 1);
			if (e < 0) {
				e = fname.length();
			}
			LuaString key = fname.substringOfEnd(b, e);
			LuaValue val = table.rawget(key);
			if (val.isNil()) { /* no such field? */
				LuaTable field = new LuaTable(); /* new table for field */
				table.rawset(key, field);
				table = field;
			} else if (!val.isTable()) {  /* field has a non-table value? */
				return null;
			} else {
				table = (LuaTable) val;
			}
		} while (e < fname.length());
		return table;
	}

	private static void modinit(LuaState state, LuaValue module, LuaString modname) throws LuaError, UnwindThrowable {
		/* module._M = module */
		OperationHelper.setTable(state, module, _M, module);
		int e = modname.lastIndexOf((byte) '.');
		OperationHelper.setTable(state, module, _NAME, modname);
		LuaValue value = (e < 0 ? Constants.EMPTYSTRING : modname.substringOfEnd(0, e + 1));
		OperationHelper.setTable(state, module, _PACKAGE, value);
	}

	/**
	 * require (modname)
	 * <p>
	 * Loads the given module. The function starts by looking into the package.loaded table to
	 * determine whether modname is already loaded. If it is, then require returns the value
	 * stored at package.loaded[modname]. Otherwise, it tries to find a loader for the module.
	 * <p>
	 * To find a loader, require is guided by the package.loaders array. By changing this array,
	 * we can change how require looks for a module. The following explanation is based on the
	 * default configuration for package.loaders.
	 * <p>
	 * First require queries package.preload[modname]. If it has a value, this value
	 * (which should be a function) is the loader. Otherwise require searches for a Lua loader
	 * using the path stored in package.path. If that also fails, it searches for a C loader
	 * using the path stored in package.cpath. If that also fails, it tries an all-in-one loader
	 * (see package.loaders).
	 * <p>
	 * Once a loader is found, require calls the loader with a single argument, modname.
	 * If the loader returns any value, require assigns the returned value to package.loaded[modname].
	 * If the loader returns no value and has not assigned any value to package.loaded[modname],
	 * then require assigns true to this entry. In any case, require returns the final value of
	 * package.loaded[modname].
	 * <p>
	 * If there is any error loading or running the module, or if it cannot find any loader for
	 * the module, then require signals an error.
	 *
	 * @param state The current lua state
	 * @param arg   Module name
	 * @return The loaded value
	 * @throws LuaError If the module cannot be loaded.
	 */
	LuaValue require(LuaState state, LuaValue arg) throws LuaError, UnwindThrowable {
		LuaString name = arg.checkLuaString();
		LuaTable loaded = loaded(state);
		LuaValue existing = OperationHelper.getTable(state, loaded, name);
		if (existing.toBoolean()) {
			if (existing == sentinel) {
				throw new LuaError("loop or previous error loading module '" + name + "'");
			}
			return existing;
		}

		/* else must load it; iterate over available loaders */
		LuaTable tbl = OperationHelper.getTable(state, packageTbl, _LOADERS).checkTable();
		StringBuilder sb = new StringBuilder();
		LuaValue chunk;
		for (int i = 1; true; i++) {
			LuaValue loader = tbl.rawget(i);
			if (loader.isNil()) {
				throw new LuaError("module '" + name + "' not found: " + name + sb);
			}

			/* call loader with module name as argument */
			chunk = OperationHelper.call(state, loader, name);
			if (chunk.isFunction()) {
				break;
			}
			if (chunk.isString()) {
				sb.append(chunk.toString());
			}
		}

		// load the module using the loader
		OperationHelper.setTable(state, loaded, name, sentinel);
		LuaValue result = OperationHelper.call(state, chunk, name);
		if (!result.isNil()) {
			OperationHelper.setTable(state, loaded, name, result);
		} else if ((result = OperationHelper.getTable(state, loaded, name)) == sentinel) {
			LuaValue value = result = Constants.TRUE;
			OperationHelper.setTable(state, loaded, name, value);
		}
		return result;
	}

	public static Varargs loadlib(LuaState state, Varargs args) throws LuaError {
		args.arg(1).checkLuaString();
		return varargsOf(Constants.NIL, valueOf("dynamic libraries not enabled"), valueOf("absent"));
	}

	private LuaValue loader_preload(LuaState state, Varargs args) throws LuaError, UnwindThrowable {
		LuaString name = args.arg(1).checkLuaString();
		LuaValue preload = state.registry().getSubTable(REGISTRY_PRELOAD);
		LuaValue val = OperationHelper.getTable(state, preload, name);
		return val.isNil() ?
			valueOf("\n\tno field package.preload['" + name + "']") :
			val;
	}

	private LuaValue loader_Lua(LuaState state, Varargs args) throws LuaError, UnwindThrowable {
		String name = args.arg(1).checkString();

		// get package path
		LuaValue pp = OperationHelper.getTable(state, packageTbl, _PATH);
		if (!pp.isString()) {
			return valueOf("package.path is not a string");
		}
		String path = pp.toString();

		// check the path elements
		int e = -1;
		int n = path.length();
		StringBuilder sb = null;
		name = name.replace('.', '/');
		while (e < n) {

			// find next template
			int b = e + 1;
			e = path.indexOf(';', b);
			if (e < 0) {
				e = path.length();
			}
			String template = path.substring(b, e);

			// create filename
			String filename = template.replace("?", name);

			// try loading the file
			Varargs v = SystemBaseLib.loadFile(state, loader, filename);
			if (v.first().isFunction()) {
				return v.first();
			}

			// report error
			if (sb == null) {
				sb = new StringBuilder();
			}
			sb.append("\n\t'").append(filename).append("': ").append(v.arg(2));
		}
		return valueOf(sb.toString());
	}

	private LuaValue loader_Java(Varargs args) throws LuaError {
		String name = args.arg(1).checkString();
		String classname = toClassname(name);
		try {
			Class<?> c = Class.forName(classname);
			return (LuaValue) c.newInstance();
		} catch (ClassNotFoundException cnfe) {
			return valueOf("\n\tno class '" + classname + "'");
		} catch (Exception e) {
			return valueOf("\n\tjava load failed on '" + classname + "', " + e);
		}
	}

	/**
	 * Convert lua filename to valid class name
	 *
	 * @param filename Name of the file
	 * @return The appropriate class name
	 */
	public static String toClassname(String filename) {
		int n = filename.length();
		int j = n;
		if (filename.endsWith(".lua")) {
			j -= 4;
		}
		for (int k = 0; k < j; k++) {
			char c = filename.charAt(k);
			if ((!isClassnamePart(c)) || (c == '/') || (c == '\\')) {
				StringBuilder sb = new StringBuilder(j);
				for (int i = 0; i < j; i++) {
					c = filename.charAt(i);
					sb.append(
						(isClassnamePart(c)) ? c :
							((c == '/') || (c == '\\')) ? '.' : '_');
				}
				return sb.toString();
			}
		}
		return n == j ? filename : filename.substring(0, j);
	}

	private static boolean isClassnamePart(char c) {
		if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
			return true;
		}
		switch (c) {
			case '.':
			case '$':
			case '_':
				return true;
			default:
				return false;
		}
	}
}