/**
 * ****************************************************************************
 * Copyright (c) 2009-2011 Luaj.org. All rights reserved.
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * ****************************************************************************
 */
package org.squiddev.cobalt.lib;


import org.squiddev.cobalt.*;
import org.squiddev.cobalt.lib.jse.JsePlatform;

import java.lang.ref.WeakReference;

import static org.squiddev.cobalt.Constants.*;
import static org.squiddev.cobalt.ValueFactory.valueOf;
import static org.squiddev.cobalt.ValueFactory.varargsOf;

/**
 * Subclass of {@link LibFunction} which implements the lua standard {@code debug}
 * library.
 * <p>
 * The debug library in luaj tries to emulate the behavior of the corresponding C-based lua library.
 * To do this, it must maintain a separate stack of calls to {@link LuaClosure} and {@link LibFunction}
 * instances.
 *
 * @see LibFunction
 * @see JsePlatform
 * @see <a href="http://www.lua.org/manual/5.1/manual.html#5.9">http://www.lua.org/manual/5.1/manual.html#5.9</a>
 */
public class DebugLib extends VarArgFunction {
	public static final boolean CALLS = (null != System.getProperty("CALLS"));
	public static final boolean TRACE = (null != System.getProperty("TRACE"));

	static final String[] NAMES = {
		"debug",
		"getfenv",
		"gethook",
		"getinfo",
		"getlocal",
		"getmetatable",
		"getregistry",
		"getupvalue",
		"setfenv",
		"sethook",
		"setlocal",
		"setmetatable",
		"setupvalue",
		"traceback",
	};

	private static final int INIT = 0;
	private static final int DEBUG = 1;
	private static final int GETFENV = 2;
	private static final int GETHOOK = 3;
	private static final int GETINFO = 4;
	private static final int GETLOCAL = 5;
	private static final int GETMETATABLE = 6;
	private static final int GETREGISTRY = 7;
	private static final int GETUPVALUE = 8;
	private static final int SETFENV = 9;
	private static final int SETHOOK = 10;
	private static final int SETLOCAL = 11;
	private static final int SETMETATABLE = 12;
	private static final int SETUPVALUE = 13;
	private static final int TRACEBACK = 14;

	/* maximum stack for a Lua function */
	private static final int MAXSTACK = 250;

	private static final LuaString LUA = valueOf("Lua");
	private static final LuaString JAVA = valueOf("Java");
	private static final LuaString QMARK = valueOf("?");
	private static final LuaString GLOBAL = valueOf("global");
	private static final LuaString LOCAL = valueOf("local");
	private static final LuaString METHOD = valueOf("method");
	private static final LuaString UPVALUE = valueOf("upvalue");
	private static final LuaString FIELD = valueOf("field");
	private static final LuaString CALL = valueOf("call");
	private static final LuaString LINE = valueOf("line");
	private static final LuaString COUNT = valueOf("count");
	private static final LuaString RETURN = valueOf("return");
	private static final LuaString TAILRETURN = valueOf("tail return");

	private static final LuaString FUNC = valueOf("func");
	private static final LuaString NUPS = valueOf("nups");
	private static final LuaString NAME = valueOf("name");
	private static final LuaString NAMEWHAT = valueOf("namewhat");
	private static final LuaString WHAT = valueOf("what");
	private static final LuaString SOURCE = valueOf("source");
	private static final LuaString SHORT_SRC = valueOf("short_src");
	private static final LuaString LINEDEFINED = valueOf("linedefined");
	private static final LuaString LASTLINEDEFINED = valueOf("lastlinedefined");
	private static final LuaString CURRENTLINE = valueOf("currentline");
	private static final LuaString ACTIVELINES = valueOf("activelines");

	private LuaTable init(LuaState state) {
		LuaTable t = new LuaTable();
		bind(state, t, DebugLib.class, NAMES, DEBUG);
		env.set(state, "debug", t);
		state.loadedPackages.set(state, "debug", t);
		return t;
	}

	@Override
	public Varargs invoke(LuaState state, Varargs args) {
		switch (opcode) {
			case INIT:
				return init(state);
			case DEBUG:
				return _debug(args);
			case GETFENV:
				return _getfenv(args);
			case GETHOOK:
				return _gethook(state, args);
			case GETINFO:
				return _getinfo(state, args, this);
			case GETLOCAL:
				return _getlocal(state, args);
			case GETMETATABLE:
				return _getmetatable(state, args);
			case GETREGISTRY:
				return _getregistry(args);
			case GETUPVALUE:
				return _getupvalue(args);
			case SETFENV:
				return _setfenv(args);
			case SETHOOK:
				return _sethook(state, args);
			case SETLOCAL:
				return _setlocal(state, args);
			case SETMETATABLE:
				return _setmetatable(state, args);
			case SETUPVALUE:
				return _setupvalue(args);
			case TRACEBACK:
				return _traceback(state, args);
			default:
				return NONE;
		}
	}

	// ------------------------ Debug Info management --------------------------
	//
	// when DEBUG_ENABLED is set to true, these functions will be called
	// by Closure instances as they process bytecodes.
	//
	// Each thread will get a DebugState attached to it by the debug library
	// which will track function calls, hook functions, etc.
	//
	public static final class DebugInfo {
		private LuaValue func;
		private LuaClosure closure;
		private Prototype prototype;
		private LuaValue[] stack;
		private Varargs varargs, extras;
		private int pc, top;

		private DebugInfo() {
			func = NIL;
		}

		private DebugInfo(LuaValue func) {
			pc = -1;
			setfunction(func);
		}

		public void setargs(Varargs varargs, LuaValue[] stack) {
			this.varargs = varargs;
			this.stack = stack;
		}

		public void setfunction(LuaValue func) {
			this.func = func;
			this.closure = func instanceof LuaClosure ? (LuaClosure) func : null;
			this.prototype = func instanceof PrototypeStorage ? ((PrototypeStorage) func).getPrototype() : null;
		}

		public void clear() {
			func = NIL;
			closure = null;
			prototype = null;
			stack = null;
			varargs = extras = null;
			pc = top = 0;
		}

		public void bytecode(int pc, Varargs extras, int top) {
			this.pc = pc;
			this.top = top;
			this.extras = extras;
		}

		public int currentline() {
			if (prototype == null) return -1;
			int[] li = prototype.lineinfo;
			return li == null || pc < 0 || pc >= li.length ? -1 : li[pc];
		}

		public LuaString[] getfunckind() {
			if (prototype == null || pc < 0) return null;
			int stackpos = (prototype.code[pc] >> 6) & 0xff;
			return getobjname(this, stackpos);
		}

		public String sourceline() {
			if (prototype == null) return func.toString();
			String s = prototype.source.toString();
			int line = currentline();
			return (s.startsWith("@") || s.startsWith("=") ? s.substring(1) : s) + ":" + line;
		}

		public String tracename() {
			LuaString[] kind = getfunckind();
			if (kind == null) {
				return "function ?";
			}
			return "function '" + kind[0].toString() + "'";
		}

		public LuaString getlocalname(int index) {
			if (prototype == null) return null;
			return prototype.getlocalname(index, pc);
		}

		@Override
		public String toString() {
			return tracename() + " " + sourceline();
		}
	}

	/**
	 * DebugState is associated with a Thread
	 */
	public static final class DebugState {
		private final WeakReference<LuaThread> thread_ref;
		private final LuaState state;
		private int debugCalls = 0;
		private DebugInfo[] debugInfo = new DebugInfo[LuaThread.MAX_CALLSTACK + 1];
		private LuaValue hookfunc;
		private boolean hookcall, hookline, hookrtrn, inhook;
		private int hookcount, hookcodes;
		private int line;

		private DebugState(LuaThread thread) {
			this.thread_ref = new WeakReference<LuaThread>(thread);
			this.state = thread.luaState;
		}

		public DebugInfo nextInfo() {
			DebugInfo di = debugInfo[debugCalls];
			if (di == null) {
				debugInfo[debugCalls] = di = new DebugInfo();
			}
			return di;
		}

		public DebugInfo pushInfo(int calls) {
			while (debugCalls < calls) {
				nextInfo();
				++debugCalls;
			}
			return debugInfo[debugCalls - 1];
		}

		public void popInfo(int calls) {
			while (debugCalls > calls) {
				debugInfo[--debugCalls].clear();
			}
		}

		void callHookFunc(DebugState ds, LuaString type, LuaValue arg) {
			if (inhook || hookfunc == null) {
				return;
			}
			inhook = true;
			try {
				int n = debugCalls;
				ds.nextInfo().setargs(arg, null);
				ds.pushInfo(n + 1).setfunction(hookfunc);
				try {
					hookfunc.call(ds.state, type, arg);
				} finally {
					ds.popInfo(n);
				}
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				inhook = false;
			}
		}

		public void sethook(LuaValue func, boolean call, boolean line, boolean rtrn, int count) {
			this.hookcount = count;
			this.hookcall = call;
			this.hookline = line;
			this.hookrtrn = rtrn;
			this.hookfunc = func;
		}

		public DebugInfo getDebugInfo() {
			try {
				return debugInfo[debugCalls - 1];
			} catch (Exception e) {
				if (debugCalls <= 0) {
					return debugInfo[debugCalls++] = new DebugInfo();
				}
				return null;
			}
		}

		public DebugInfo getDebugInfo(int level) {
			return level < 0 || level >= debugCalls ? null : debugInfo[debugCalls - level - 1];
		}

		public DebugInfo findDebugInfo(LuaValue func) {
			for (int i = debugCalls; --i >= 0; ) {
				if (debugInfo[i].func == func) {
					return debugInfo[i];
				}
			}
			return new DebugInfo(func);
		}

		@Override
		public String toString() {
			LuaThread thread = thread_ref.get();
			return thread != null ? DebugLib.traceback(thread, 0) : "orphaned thread";
		}

		public boolean inHook() {
			return inhook;
		}
	}

	public static DebugState getDebugState(LuaThread thread) {
		if (thread.debugState == null) {
			thread.debugState = new DebugState(thread);
		}
		return thread.debugState;
	}

	public static DebugState getDebugState(LuaState state) {
		return getDebugState(state.currentThread);
	}

	/**
	 * Called by Closures and recursing java functions on entry
	 *
	 * @param thread the thread for the call
	 * @param calls  the number of calls in the call stack
	 * @param func   the function called
	 */
	public static void debugOnCall(LuaThread thread, int calls, LuaFunction func) {
		DebugState ds = getDebugState(thread);
		if (ds.inhook) {
			return;
		}
		DebugInfo di = ds.pushInfo(calls);
		di.setfunction(func);
		if (CALLS) System.out.println("calling " + func);
		if (ds.hookcall) {
			ds.callHookFunc(ds, CALL, NIL);
		}
	}

	/**
	 * Called by Closures and recursing java functions on return
	 *
	 * @param thread the thread for the call
	 * @param calls  the number of calls in the call stack
	 */
	public static void debugOnReturn(LuaThread thread, int calls) {
		DebugState ds = getDebugState(thread);
		if (ds.inhook) {
			return;
		}
		if (CALLS) System.out.println("returning");
		try {
			if (ds.hookrtrn) {
				ds.callHookFunc(ds, RETURN, NIL);
			}
		} finally {
			ds.popInfo(calls);
		}
	}

	/**
	 * Called by Closures on bytecode execution
	 *
	 * @param ds     Debug state
	 * @param di     Debug info
	 * @param pc     Current program counter
	 * @param extras Extra arguments
	 * @param top    The top of the callstack
	 */
	public static void debugBytecode(DebugState ds, DebugInfo di, int pc, Varargs extras, int top) {
		if (TRACE) Print.printState(di.closure, pc, di.stack, top, di.varargs);
		di.bytecode(pc, extras, top);
		if (ds.hookcount > 0) {
			if (++ds.hookcodes >= ds.hookcount) {
				ds.hookcodes = 0;
				ds.callHookFunc(ds, COUNT, NIL);
			}
		}
		if (ds.hookline) {
			int newline = di.currentline();
			if (newline != ds.line) {
				int c = di.prototype.code[pc];
				if ((c & 0x3f) != Lua.OP_JMP || ((c >>> 14) - 0x1ffff) >= 0) {
					ds.line = newline;
					ds.callHookFunc(ds, LINE, valueOf(newline));
				}
			}
		}
	}

	// ------------------- library function implementations -----------------

	// j2se subclass may wish to override and provide actual console here.
	// j2me platform has not System.in to provide console.
	static Varargs _debug(Varargs args) {
		return NONE;
	}

	static Varargs _gethook(LuaState state, Varargs args) {
		int a = 1;
		LuaThread thread = args.arg(a).isThread() ? args.arg(a++).checkThread() : state.currentThread;
		DebugState ds = getDebugState(thread);
		return varargsOf(
			ds.hookfunc,
			valueOf((ds.hookcall ? "c" : "") + (ds.hookline ? "l" : "") + (ds.hookrtrn ? "r" : "")),
			valueOf(ds.hookcount));
	}

	static Varargs _sethook(LuaState state, Varargs args) {
		int a = 1;
		LuaThread thread = args.arg(a).isThread() ? args.arg(a++).checkThread() : state.currentThread;
		int i1 = a++;
		LuaValue func = args.arg(i1).optFunction(null);
		int i3 = a++;
		String str = args.arg(i3).optString("");
		int i2 = a++;
		int count = args.arg(i2).optInteger(0);
		boolean call = false, line = false, rtrn = false;
		for (int i = 0; i < str.length(); i++) {
			switch (str.charAt(i)) {
				case 'c':
					call = true;
					break;
				case 'l':
					line = true;
					break;
				case 'r':
					rtrn = true;
					break;
			}
		}
		getDebugState(thread).sethook(func, call, line, rtrn, count);
		return NONE;
	}

	private static Varargs _getfenv(Varargs args) {
		LuaValue object = args.first();
		LuaValue env = object.getfenv();
		return env != null ? env : NIL;
	}

	private static Varargs _setfenv(Varargs args) {
		LuaValue object = args.first();
		LuaTable table = args.arg(2).checkTable();
		object.setfenv(table);
		return object;
	}

	protected static Varargs _getinfo(LuaState state, Varargs args, LuaValue level0func) {
		int a = 1;
		LuaThread thread = args.arg(a).isThread() ? args.arg(a++).checkThread() : state.currentThread;
		LuaValue func = args.arg(a++);
		int i1 = a++;
		String what = args.arg(i1).optString("nSluf");

		// find the stack info
		DebugState ds = getDebugState(thread);
		DebugInfo di = null;
		if (func.isNumber()) {
			int level = func.checkInteger();
			di = level > 0 ?
				ds.getDebugInfo(level - 1) :
				new DebugInfo(level0func);
		} else {
			di = ds.findDebugInfo(func.checkFunction());
		}
		if (di == null) {
			return NIL;
		}

		// start a table
		LuaTable info = new LuaTable();
		LuaClosure c = di.closure;
		for (int i = 0, j = what.length(); i < j; i++) {
			switch (what.charAt(i)) {
				case 'S': {
					if (c != null) {
						Prototype p = c.p;
						info.set(state, WHAT, LUA);
						info.set(state, SOURCE, p.source);
						info.set(state, SHORT_SRC, valueOf(sourceshort(p)));
						info.set(state, LINEDEFINED, valueOf(p.linedefined));
						info.set(state, LASTLINEDEFINED, valueOf(p.lastlinedefined));
					} else {
						String shortName = di.func.toString();
						LuaString name = LuaString.valueOf("[Java] " + shortName);
						info.set(state, WHAT, JAVA);
						info.set(state, SOURCE, name);
						info.set(state, SHORT_SRC, valueOf(shortName));
						info.set(state, LINEDEFINED, MINUSONE);
						info.set(state, LASTLINEDEFINED, MINUSONE);
					}
					break;
				}
				case 'l': {
					int line = di.currentline();
					info.set(state, CURRENTLINE, valueOf(line));
					break;
				}
				case 'u': {
					info.set(state, NUPS, valueOf(c != null ? c.p.nups : 0));
					break;
				}
				case 'n': {
					LuaString[] kind = di.getfunckind();
					info.set(state, NAME, kind != null ? kind[0] : QMARK);
					info.set(state, NAMEWHAT, kind != null ? kind[1] : EMPTYSTRING);
					break;
				}
				case 'f': {
					info.set(state, FUNC, di.func);
					break;
				}
				case 'L': {
					LuaTable lines = new LuaTable();
					info.set(state, ACTIVELINES, lines);
//					if ( di.luainfo != null ) {
//						int line = di.luainfo.currentline();
//						if ( line >= 0 )
//							lines.set(1, IntValue.valueOf(line));
//					}
					break;
				}
			}
		}
		return info;
	}

	public static String sourceshort(Prototype p) {
		String name = p.source.toString();
		if (name.startsWith("@") || name.startsWith("=")) {
			name = name.substring(1);
		} else if (name.startsWith("\033")) {
			name = "binary string";
		}
		return name;
	}

	private static Varargs _getlocal(LuaState state, Varargs args) {
		int a = 1;
		LuaThread thread = args.arg(a).isThread() ? args.arg(a++).checkThread() : state.currentThread;
		int i1 = a++;
		int level = args.arg(i1).checkInteger();
		int i = a++;
		int local = args.arg(i).checkInteger();

		DebugState ds = getDebugState(thread);
		DebugInfo di = ds.getDebugInfo(level - 1);
		LuaString name = (di != null ? di.getlocalname(local) : null);
		if (name != null) {
			LuaValue value = di.stack[local - 1];
			return varargsOf(name, value);
		} else {
			return NIL;
		}
	}

	private static Varargs _setlocal(LuaState state, Varargs args) {
		int a = 1;
		LuaThread thread = args.arg(a).isThread() ? args.arg(a++).checkThread() : state.currentThread;
		int i1 = a++;
		int level = args.arg(i1).checkInteger();
		int i = a++;
		int local = args.arg(i).checkInteger();
		LuaValue value = args.arg(a++);

		DebugState ds = getDebugState(thread);
		DebugInfo di = ds.getDebugInfo(level - 1);
		LuaString name = (di != null ? di.getlocalname(local) : null);
		if (name != null) {
			di.stack[local - 1] = value;
			return name;
		} else {
			return NIL;
		}
	}

	private static LuaValue _getmetatable(LuaState state, Varargs args) {
		LuaValue object = args.arg(1);
		LuaValue mt = object.getMetatable(state);
		return mt != null ? mt : NIL;
	}

	private static Varargs _setmetatable(LuaState state, Varargs args) {
		LuaValue object = args.arg(1);
		try {
			LuaValue mt = args.arg(2).optTable(null);
			switch (object.type()) {
				case TNIL:
					state.nilMetatable = mt;
					break;
				case TNUMBER:
					state.numberMetatable = mt;
					break;
				case TBOOLEAN:
					state.booleanMetatable = mt;
					break;
				case TSTRING:
					state.stringMetatable = mt;
					break;
				case TFUNCTION:
					state.functionMetatable = mt;
					break;
				case TTHREAD:
					state.threadMetatable = mt;
					break;
				default:
					object.setMetatable(state, mt);
			}
			return TRUE;
		} catch (LuaError e) {
			return varargsOf(FALSE, valueOf(e.toString()));
		}
	}

	private static Varargs _getregistry(Varargs args) {
		return new LuaTable();
	}

	private static LuaString findupvalue(LuaClosure c, int up) {
		if (c.upValues != null && up > 0 && up <= c.upValues.length) {
			if (c.p.upvalues != null && up <= c.p.upvalues.length) {
				return c.p.upvalues[up - 1];
			} else {
				return LuaString.valueOf("." + up);
			}
		}
		return null;
	}

	private static Varargs _getupvalue(Varargs args) {
		LuaValue func = args.arg(1).checkFunction();
		int up = args.arg(2).checkInteger();
		if (func instanceof LuaClosure) {
			LuaClosure c = (LuaClosure) func;
			LuaString name = findupvalue(c, up);
			if (name != null) {
				return varargsOf(name, c.upValues[up - 1].getValue());
			}
		}
		return NIL;
	}

	private static LuaValue _setupvalue(Varargs args) {
		LuaValue func = args.arg(1).checkFunction();
		int up = args.arg(2).checkInteger();
		LuaValue value = args.arg(3);
		if (func instanceof LuaClosure) {
			LuaClosure c = (LuaClosure) func;
			LuaString name = findupvalue(c, up);
			if (name != null) {
				c.upValues[up - 1].setValue(value);
				return name;
			}
		}
		return NIL;
	}

	private static LuaValue _traceback(LuaState state, Varargs args) {
		int a = 1;
		LuaThread thread = args.arg(a).isThread() ? args.arg(a++).checkThread() : state.currentThread;
		int i1 = a++;
		String message = args.arg(i1).optString(null);
		int i = a++;
		int level = args.arg(i).optInteger(1);
		String tb = DebugLib.traceback(thread, level - 1);
		return valueOf(message != null ? message + "\n" + tb : tb);
	}

	// =================== public utilities ====================

	/**
	 * Get a traceback for a particular thread.
	 *
	 * @param thread LuaThread to provide stack trace for
	 * @param level  0-based level to start reporting on
	 * @return String containing the stack trace.
	 */
	public static String traceback(LuaThread thread, int level) {
		StringBuilder sb = new StringBuilder();
		DebugState ds = getDebugState(thread);
		sb.append("stack traceback:");
		DebugInfo di = ds.getDebugInfo(level);
		if (di != null) {
			sb.append("\n\t");
			sb.append(di.sourceline());
			sb.append(": in ");
			while ((di = ds.getDebugInfo(++level)) != null) {
				sb.append(di.tracename());
				sb.append("\n\t");
				sb.append(di.sourceline());
				sb.append(": in ");
			}
			sb.append("main chunk");
		}
		return sb.toString();
	}


	/**
	 * Get file and line for the nearest calling closure.
	 *
	 * @param thread The current thread
	 * @return String identifying the file and line of the nearest lua closure,
	 * or the function name of the Java call if no closure is being called.
	 */
	public static String fileline(LuaThread thread) {
		DebugState ds = getDebugState(thread);
		DebugInfo di;
		for (int i = 0, n = ds.debugCalls; i < n; i++) {
			di = ds.getDebugInfo(i);
			if (di != null && di.func.isClosure()) {
				return di.sourceline();
			}
		}
		return fileline(thread, 0);
	}

	public static String fileline(LuaThread thread, int level) {
		DebugState ds = getDebugState(thread);
		DebugInfo di = ds.getDebugInfo(level);
		return di != null ? di.sourceline() : null;
	}

	// =======================================================

	static void lua_assert(boolean x) {
		if (!x) throw new RuntimeException("lua_assert failed");
	}


	// return StrValue[] { name, namewhat } if found, null if not
	static LuaString[] getobjname(DebugInfo di, int stackpos) {
		LuaString name;
		Prototype p = di.prototype;
		if (p != null) { // a Lua function?
			int pc = di.pc; // currentpc(L, ci);
			int i;// Instruction i;
			name = p.getlocalname(stackpos + 1, pc);
			if (name != null) /* is a local? */ {
				return new LuaString[]{name, LOCAL};
			}
			i = symbexec(p, pc, stackpos); /* try symbolic execution */
			lua_assert(pc != -1);
			switch (Lua.GET_OPCODE(i)) {
				case Lua.OP_GETGLOBAL: {
					int g = Lua.GETARG_Bx(i); /* global index */
					// lua_assert(p.k[g].isString());
					return new LuaString[]{p.k[g].strvalue(), GLOBAL};
				}
				case Lua.OP_MOVE: {
					int a = Lua.GETARG_A(i);
					int b = Lua.GETARG_B(i); /* move from `b' to `a' */
					if (b < a) {
						return getobjname(di, b); /* get name for `b' */
					}
					break;
				}
				case Lua.OP_GETTABLE: {
					int k = Lua.GETARG_C(i); /* key index */
					name = kname(p, k);
					return new LuaString[]{name, FIELD};
				}
				case Lua.OP_GETUPVAL: {
					int u = Lua.GETARG_B(i); /* upvalue index */
					name = u < p.upvalues.length ? p.upvalues[u] : QMARK;
					return new LuaString[]{name, UPVALUE};
				}
				case Lua.OP_SELF: {
					int k = Lua.GETARG_C(i); /* key index */
					name = kname(p, k);
					return new LuaString[]{name, METHOD};
				}
				default:
					break;
			}
		}
		return null; /* no useful name found */
	}

	static LuaString kname(Prototype p, int c) {
		if (Lua.ISK(c) && p.k[Lua.INDEXK(c)].isString()) {
			return p.k[Lua.INDEXK(c)].strvalue();
		} else {
			return QMARK;
		}
	}

	static boolean checkreg(Prototype pt, int reg) {
		return (reg < pt.maxstacksize);
	}

	static boolean precheck(Prototype pt) {
		if (!(pt.maxstacksize <= MAXSTACK)) return false;
		lua_assert(pt.numparams + (pt.is_vararg & Lua.VARARG_HASARG) <= pt.maxstacksize);
		lua_assert((pt.is_vararg & Lua.VARARG_NEEDSARG) == 0
			|| (pt.is_vararg & Lua.VARARG_HASARG) != 0);
		return pt.upvalues.length <= pt.nups && (pt.lineinfo.length == pt.code.length || pt.lineinfo.length == 0) && Lua.GET_OPCODE(pt.code[pt.code.length - 1]) == Lua.OP_RETURN;
	}

	static boolean checkopenop(Prototype pt, int pc) {
		int i = pt.code[(pc) + 1];
		switch (Lua.GET_OPCODE(i)) {
			case Lua.OP_CALL:
			case Lua.OP_TAILCALL:
			case Lua.OP_RETURN:
			case Lua.OP_SETLIST: {
				return Lua.GETARG_B(i) == 0;
			}
			default:
				return false; /* invalid instruction after an open call */
		}
	}

	//static int checkArgMode (Prototype pt, int r, enum OpArgMask mode) {
	static boolean checkArgMode(Prototype pt, int r, int mode) {
		switch (mode) {
			case Lua.OpArgN:
				if (!(r == 0)) return false;
				break;
			case Lua.OpArgU:
				break;
			case Lua.OpArgR:
				checkreg(pt, r);
				break;
			case Lua.OpArgK:
				if (!(Lua.ISK(r) ? Lua.INDEXK(r) < pt.k.length : r < pt.maxstacksize)) return false;
				break;
		}
		return true;
	}


	// return last instruction, or 0 if error
	static int symbexec(Prototype pt, int lastpc, int reg) {
		int pc;
		int last; /* stores position of last instruction that changed `reg' */
		last = pt.code.length - 1; /*
									 * points to final return (a `neutral'
									 * instruction)
									 */
		if (!(precheck(pt))) return 0;
		for (pc = 0; pc < lastpc; pc++) {
			int i = pt.code[pc];
			int op = Lua.GET_OPCODE(i);
			int a = Lua.GETARG_A(i);
			int b = 0;
			int c = 0;
			if (!(op < Lua.NUM_OPCODES)) return 0;
			if (!checkreg(pt, a)) return 0;
			switch (Lua.getOpMode(op)) {
				case Lua.iABC: {
					b = Lua.GETARG_B(i);
					c = Lua.GETARG_C(i);
					if (!(checkArgMode(pt, b, Lua.getBMode(op)))) return 0;
					if (!(checkArgMode(pt, c, Lua.getCMode(op)))) return 0;
					break;
				}
				case Lua.iABx: {
					b = Lua.GETARG_Bx(i);
					if (Lua.getBMode(op) == Lua.OpArgK) {
						if (!(b < pt.k.length)) return 0;
					}
					break;
				}
				case Lua.iAsBx: {
					b = Lua.GETARG_sBx(i);
					if (Lua.getBMode(op) == Lua.OpArgR) {
						int dest = pc + 1 + b;
						if (!(0 <= dest && dest < pt.code.length)) return 0;
						if (dest > 0) {
						/* cannot jump to a setlist count */
							int d = pt.code[dest - 1];
							if ((Lua.GET_OPCODE(d) == Lua.OP_SETLIST && Lua.GETARG_C(d) == 0)) return 0;
						}
					}
					break;
				}
			}
			if (Lua.testAMode(op)) {
				if (a == reg) {
					last = pc; /* change register `a' */
				}
			}
			if (Lua.testTMode(op)) {
				if (!(pc + 2 < pt.code.length)) return 0; /* check skip */
				if (!(Lua.GET_OPCODE(pt.code[pc + 1]) == Lua.OP_JMP)) return 0;
			}
			switch (op) {
				case Lua.OP_LOADBOOL: {
					if (!(c == 0 || pc + 2 < pt.code.length)) return 0; /* check its jump */
					break;
				}
				case Lua.OP_LOADNIL: {
					if (a <= reg && reg <= b) {
						last = pc; /* set registers from `a' to `b' */
					}
					break;
				}
				case Lua.OP_GETUPVAL:
				case Lua.OP_SETUPVAL: {
					if (!(b < pt.nups)) return 0;
					break;
				}
				case Lua.OP_GETGLOBAL:
				case Lua.OP_SETGLOBAL: {
					if (!(pt.k[b].isString())) return 0;
					break;
				}
				case Lua.OP_SELF: {
					if (!checkreg(pt, a + 1)) return 0;
					if (reg == a + 1) {
						last = pc;
					}
					break;
				}
				case Lua.OP_CONCAT: {
					if (!(b < c)) return 0; /* at least two operands */
					break;
				}
				case Lua.OP_TFORLOOP: {
					if (!(c >= 1)) return 0; /* at least one result (control variable) */
					if (!checkreg(pt, a + 2 + c)) return 0; /* space for results */
					if (reg >= a + 2) {
						last = pc; /* affect all regs above its base */
					}
					break;
				}
				case Lua.OP_FORLOOP:
				case Lua.OP_FORPREP:
					if (!checkreg(pt, a + 3)) return 0;
				/* go through */
				case Lua.OP_JMP: {
					int dest = pc + 1 + b;
				/* not full check and jump is forward and do not skip `lastpc'? */
					if (reg != Lua.NO_REG && pc < dest && dest <= lastpc) {
						pc += b; /* do the jump */
					}
					break;
				}
				case Lua.OP_CALL:
				case Lua.OP_TAILCALL: {
					if (b != 0) {
						if (!checkreg(pt, a + b - 1)) return 0;
					}
					c--; /* c = num. returns */
					if (c == Lua.LUA_MULTRET) {
						if (!(checkopenop(pt, pc))) return 0;
					} else if (c != 0) {
						if (!checkreg(pt, a + c - 1)) return 0;
					}
					if (reg >= a) {
						last = pc; /* affect all registers above base */
					}
					break;
				}
				case Lua.OP_RETURN: {
					b--; /* b = num. returns */
					if (b > 0) {
						if (!checkreg(pt, a + b - 1)) return 0;
					}
					break;
				}
				case Lua.OP_SETLIST: {
					if (b > 0) {
						if (!checkreg(pt, a + b)) return 0;
					}
					if (c == 0) {
						pc++;
					}
					break;
				}
				case Lua.OP_CLOSURE: {
					int nup, j;
					if (!(b < pt.p.length)) return 0;
					nup = pt.p[b].nups;
					if (!(pc + nup < pt.code.length)) return 0;
					for (j = 1; j <= nup; j++) {
						int op1 = Lua.GET_OPCODE(pt.code[pc + j]);
						if (!(op1 == Lua.OP_GETUPVAL || op1 == Lua.OP_MOVE)) return 0;
					}
					if (reg != Lua.NO_REG) /* tracing? */ {
						pc += nup; /* do not 'execute' these pseudo-instructions */
					}
					break;
				}
				case Lua.OP_VARARG: {
					if (!((pt.is_vararg & Lua.VARARG_ISVARARG) != 0
						&& (pt.is_vararg & Lua.VARARG_NEEDSARG) == 0)) {
						return 0;
					}
					b--;
					if (b == Lua.LUA_MULTRET) {
						if (!(checkopenop(pt, pc))) return 0;
					}
					if (!checkreg(pt, a + b - 1)) return 0;
					break;
				}
				default:
					break;
			}
		}
		return pt.code[last];
	}

}