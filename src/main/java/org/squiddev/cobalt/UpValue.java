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
package org.squiddev.cobalt;


/**
 * Upvalue used with Closure formulation
 *
 * @see LuaClosure
 * @see Prototype
 */
public final class UpValue {

	LuaValue[] array; // initially the stack, becomes a holder
	int index;

	/**
	 * Create an upvalue relative to a stack
	 *
	 * @param stack the stack
	 * @param index the index on the stack for the upvalue
	 */
	public UpValue(LuaValue[] stack, int index) {
		this.array = stack;
		this.index = index;
	}

	/**
	 * Convert this upvalue to a Java String
	 *
	 * @return the Java String for this upvalue.
	 * @see LuaValue#toString()
	 */
	@Override
	public String toString() {
		return array[index].toString();
	}

	/**
	 * Get the value of the upvalue
	 *
	 * @return the {@link LuaValue} for this upvalue
	 */
	public final LuaValue getValue() {
		return array[index];
	}

	/**
	 * Set the value of the upvalue
	 *
	 * @param value {@link LuaValue} to set it to
	 */
	public final void setValue(LuaValue value) {
		array[index] = value;
	}

	/**
	 * Close this upvalue so it is no longer on the stack
	 */
	public final void close() {
		array = new LuaValue[]{array[index]};
		index = 0;
	}
}