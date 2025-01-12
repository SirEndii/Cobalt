describe("Lua tables", function()
	local maxI, minI
	-- Not clear what the definition of maxinteger is on Cobalt, so for now we
	-- just assume it matches Java's version.
	if math.maxinteger then
		maxI, minI = math.maxinteger, math.mininteger
	else
		maxI, minI = 2^31 - 1, -2^31
	end

	-- Create a slice of a table - the returned table is a view of the original contents, not a copy.
	--
	-- This is mostly intended for testing functions which use metamethods.
	local function make_slice(tbl, start, len)
		return setmetatable({}, {
			__len = function() return len end,
			__index = function(self, i)
				if i >= 1 and i <= len then return tbl[start + i - 1] end
			end,
			__newindex = function(self, i, x)
				if i < 1 or i > len then error("index out of bounds", 2) end
				tbl[start + i - 1] = x
			end,
		})
	end

	-- Count the number of items in a table
	local function size(t)
		local n = 0
		for _ in pairs(t) do n = n + 1 end
		return n
	end

	describe("can have keys set", function()
		describe("invalid keys", function()
			it("are rejected", function()
				local t = {}
				expect.error(function() t[nil] = true end):str_match("table index is nil$")
				expect.error(function() t[0/0] = true end):str_match("table index is NaN$")
			end)

			it("are allowed on tables with __newindex :lua>=5.2", function()
				local t = setmetatable({}, {__newindex = function() end})
				t[nil] = true
				t[0/0] = true
			end)
		end)
	end)

	describe("have a length operator", function()
		it("behaves identically to PUC Lua on sparse tables", function()
			-- Ensure the length operator on sparse tables behaves identically to PUC Lua.
			expect(#{ 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, nil, 17, 18, [33] = {} }):eq(18)
			expect(#{ 1, 2, 3, nil, 5, nil, nil, 8, 9 }):eq(9)
			expect(#{ 1, 2, 3, nil, 5, nil, 7, 8 }):eq(8)
			expect(#{ 1, 2, 3, nil, 5, nil, 7, 8, 9 }):eq(9)
			expect(#{ 1, nil, [2] = 2, 3 }):eq(3)
		end)
	end)

	describe("weak tables", function()
		local function setmode(t, mode)
			-- Note we have to create a new metatable here, as Cobalt doesn't
			-- pick up mutations to the original table.
			return setmetatable(t, { __mode = mode })
		end

		-- Create a "new" string. This avoids interning via the constant table
		-- and the short string optimisation.
		local function mk_str(x) return x .. ("."):rep(100) end

		describe("with weak keys", function()
			it("will clear keys", function()
				local k = {}

				-- Set up our table
				local t = setmode({}, "k")
				t[k] = "value"

				expect(size(t)):eq(1)
				expect(t[k]):eq("value")

				-- Collect garbage with value on the stack - key should be present.
				collectgarbage()

				expect(size(t)):eq(1)
				expect(t[k]):eq("value")

				-- Collect garbage with value not on the stack - key should be absent.
				k = nil
				collectgarbage()

				expect(size(t)):eq(0)
			end)

			it("normal tables can become weak", function()
				-- Set up our table
				local t = {[{}] = "value"}
				expect(size(t)):eq(1)

				setmode(t, "k")
				collectgarbage()
				expect(size(t)):eq(0)
			end)

			it("weak tables can become strong", function()
				-- Create a weak table then GC to remove one of the keys.
				local t1 = {}
				local t = setmode({[t1] = "t1", [{}] = "t2"}, "k")
				collectgarbage()
				expect(size(t)):eq(1)

				-- Make the table strong.
				setmode(t, nil)

				-- Clear our table
				t1 = nil
				collectgarbage()
				expect(size(t)):eq(1)

				local k, v = next(t)
				expect(v):eq("t1")
			end)
		end)

		describe("with weak values", function()
			it("will clear their values", function()
				local s1 = mk_str "test string"
				local t1, t2 = {}, {}
				local t = setmode({}, "v")

				-- Set up our table
				t["string"] = s1
				t["table"] = t1
				t[1] = t2

				expect(#t):eq(1)
				expect(size(t)):eq(3)

				expect(t["string"]):eq(s1)
				expect(t["table"]):eq(t1)
				expect(t[1]):eq(t2)

				-- Collect garbage once with these values still on the stack - no change.
				collectgarbage()

				expect(t["string"]):eq(s1)
				expect(t["table"]):eq(t1)
				expect(t[1]):eq(t2)

				-- Collect garbage with these values on longer on the stack - the table should be cleared of GC values.
				s1, t1, t2 = nil, nil, nil

				--[[
				Note [Clearing stack values]
				~~~~~~~~~~~~~~~~~~~~~~~~~
				Some of these values may still be on the stack in an argument position. This in a Cobalt-specific bug:
				it does not occur in PUC Lua, as they only GC up to the stack top. We obviously cannot control that in
				Java, and clearing the stack would come with a small performance cost.
				]]
				do local nasty1, nasty2, nasty3 = nil, nil, nil end

				collectgarbage()

				expect(#t):eq(0)
				expect(size(t)):eq(1)

				expect(t["string"]):ne(nil)
				expect(t["table"]):eq(nil)
				expect(t[1]):eq(nil)
			end)

			it("can change mode", function()
				local t = { {}, "preserved" }

				-- Table contains our value
				expect(t[1]):ne(nil)

				-- Change mode and collect garbage - value should be removed.
				setmode(t, "v")
				collectgarbage()

				expect(t[1]):eq(nil)
				expect(t[2]):eq("preserved")
			end)
		end)

		describe("with weak keys and values", function()
			it("will clear values", function()
				local a1 = {}
				local k1, v1 = {}, {}
				local k2, v2 = {}, {}

				-- Set up our table
				local t = setmode({ a1, [k1] = v1, [k2] = v2 }, "kv")
				expect(size(t)):eq(3)
				expect(t[1]):eq(a1)
				expect(t[k1]):eq(v1)
				expect(t[k2]):eq(v2)

				-- Collect garbage once with entries still on the stack - no change.
				collectgarbage()

				expect(size(t)):eq(3)
				expect(t[1]):eq(a1)
				expect(t[k1]):eq(v1)
				expect(t[k2]):eq(v2)

				-- Collect garbage with these entries on longer on the stack - the table should be cleared.
				a1, k1, v2 = nil, nil, nil
				-- See Note [Clearing stack values]
				do local nasty1, nasty2, nasty3 = nil, nil, nil end
				collectgarbage()

				expect(size(t)):eq(0)
			end)

			it("can change mode", function()
				-- Set up our table
				local t = setmode({ key = {}, [{}] = "value", {}, preserved = true })
				collectgarbage()
				expect(size(t)):eq(4)

				-- Change to a weak table and ensure it is empty
				setmode(t, "kv")
				collectgarbage()

				expect(size(t)):eq(1)
				expect(t.preserved):eq(true)
			end)
		end)
	end)

	describe("rawlen :lua>=5.2", function()
		it("behaves identically to PUC Lua on sparse tables", function()
			expect(rawlen({[1]="e",[2]="a",[3]="b",[4]="c"})):eq(4)
			expect(rawlen({[1]="e",[2]="a",[3]="b",[4]="c",[8]="f"})):eq(8)
		end)
	end)

	describe("rawset", function()
		it("rejects a nil key", function()
			expect.error(rawset, {}, nil, 1):str_match("table index is nil")
		end)

		it("rejects a nan key", function()
			expect.error(rawset, {}, 0/0, 1):str_match("table index is NaN")
		end)
	end)

	describe("table.getn :lua==5.1", function()
		it("behaves identically to PUC Lua on sparse tables", function()
			expect(table.getn({[1]="e",[2]="a",[3]="b",[4]="c"})):eq(4)
			expect(table.getn({[1]="e",[2]="a",[3]="b",[4]="c",[8]="f"})):eq(8)
		end)
	end)

	describe("table.maxn :lua==5.1", function()
		it("behaves identically to PUC Lua on sparse tables", function()
			expect(table.maxn({[1]="e",[2]="a",[3]="b",[4]="c"})):eq(4)
			expect(table.maxn({[1]="e",[2]="a",[3]="b",[4]="c",[8]="f"})):eq(8)
		end)
	end)

	-- Test both directly on a table and via a proxy
	local function direct_and_proxy(name, func)
		it(name, function()
			local tbl = {}
			return func(function(x) return x end)
		end)

		it(name .. " (with metatable) :lua>=5.3", function()
			return func(function(tbl) return setmetatable({}, {
				__len = function() return #tbl end,
				__index = function(_, k) return tbl[k] end,
				__newindex = function(_, k, v) tbl[k] = v end,
			}) end)
		end)
	end

	describe("table.insert", function()
		direct_and_proxy("inserts at the beginning of the list", function(wrap)
			local function mk_expected(size)
				local out = {}
				for i = 1, size do out[i] = "Value #" .. (size - i + 1) end
				return out
			end

			local tbl = {}
			local proxy = wrap(tbl)
			for i = 1, 32 do
				table.insert(proxy, 1, "Value #" .. i)
				expect(tbl):same(mk_expected(i))
			end
		end)

		direct_and_proxy("inserts at the end of the list", function(wrap)
			local function mk_expected(size)
				local out = {}
				for i = 1, size do out[i] = "Value #" .. i end
				return out
			end

			local tbl = {}
			local proxy = wrap(tbl)
			for i = 1, 32 do
				table.insert(proxy, "Value #" .. i)
				expect(tbl):same(mk_expected(i))
			end
		end)

		direct_and_proxy("inserts in the middle of the list", function(wrap)
			local function mk_expected(size)
				local out = {}
				for i = 1, math.ceil(size / 2) do out[i] = "Value #" .. (i * 2 - 1) end
				for i = 1, math.floor(size / 2) do out[size - i + 1] = "Value #" .. (i * 2) end
				return out
			end

			local tbl = {}
			local proxy = wrap(tbl)
			for i = 1, 32 do
				table.insert(proxy, math.floor(i / 2) + 1, "Value #" .. i)
				expect(tbl):same(mk_expected(i))
			end
		end)
	end)

	describe("table.remove", function()
		it("removes values at 0 :lua>=5.2", function()
			local a = {[0] = "ban"}
			expect(#a):eq(0)
			expect(table.remove(a)):eq("ban")
			expect(a[0]):eq(nil)
		end)

		local function mk_filled()
			local out = {}
			for i = 1, 32 do out[i] = "Value #" .. i end
			return out
		end

		direct_and_proxy("remove at beginning of list", function(wrap)
			local function mk_expect(size)
				local out = {}
				for i = 1, size do out[i] = "Value #" .. (32 - size + i) end
				return out
			end

			local tbl = mk_filled(size)
			local proxy = wrap(tbl)

			for i = 1, 32 do
				expect(table.remove(proxy, 1)):eq("Value #" .. i)
				expect(tbl):same(mk_expect(32 - i))
			end
		end)

		direct_and_proxy("remove at end of list", function(wrap)
			local function mk_expect(size)
				local out = {}
				for i = 1, size do out[i] = "Value #" .. i end
				return out
			end

			local tbl = mk_filled(size)
			local proxy = wrap(tbl)

			for i = 1, 32 do
				expect(table.remove(proxy)):eq("Value #" .. (32 - i + 1))
				expect(tbl):same(mk_expect(32 - i))
			end
		end)
	end)

	describe("table.insert/table.remove PUC Lua tests", function()
		-- Combined tests of table.insert and table.remove from nextvar.

		-- Some assertions are commented out here, as we don't do the bounds checks that Lua 5.2 do.

		local function test(a)
			-- expect.error(table.insert, a, 2, 20)
			table.insert(a, 10); table.insert(a, 2, 20)
			table.insert(a, 1, -1); table.insert(a, 40)
			table.insert(a, #a+1, 50)
			table.insert(a, 2, -2)
			expect(a[2]):ne(nil)
			expect(a["2"]):eq(nil)
			-- expect.error(table.insert, a, 0, 20)
			-- expect.error(table.insert, a, #a + 2, 20)
			expect(table.remove(a,1)):eq(-1)
			expect(table.remove(a,1)):eq(-2)
			expect(table.remove(a,1)):eq(10)
			expect(table.remove(a,1)):eq(20)
			expect(table.remove(a,1)):eq(40)
			expect(table.remove(a,1)):eq(50)
			expect(table.remove(a,1)):eq(nil)
			expect(table.remove(a)):eq(nil)
			expect(table.remove(a, #a)):eq(nil)
		end

		it("test #1", function()
			local a = {n=0, [-7] = "ban"}
			test(a)
			expect(a.n):eq(0)
			expect(a[-7]):eq("ban")
		end)

		it("test #2", function()
			local a = {[-7] = "ban"};
			test(a)
			expect(a.n):eq(nil)
			expect(#a):eq(0)
			expect(a[-7] == "ban")
		end)

		it("test #3", function()
			local a = {[-1] = "ban"}
			test(a)
			expect(a.n):eq(nil)
			expect(table.remove(a)):eq(nil)
			expect(a[-1]):eq("ban")
		end)

		it("test #4", function()
			local a = {}
			table.insert(a, 1, 10); table.insert(a, 1, 20); table.insert(a, 1, -1)
			expect(table.remove(a)):eq(10)
			expect(table.remove(a)):eq(20)
			expect(table.remove(a)):eq(-1)
			expect(table.remove(a)):eq(nil)
		end)

		it("test #4", function()
			local a = {'c', 'd'}
			table.insert(a, 3, 'a')
			table.insert(a, 'b')
			expect(table.remove(a, 1)):eq('c')
			expect(table.remove(a, 1)):eq('d')
			expect(table.remove(a, 1)):eq('a')
			expect(table.remove(a, 1)):eq('b')
			expect(table.remove(a, 1)):eq(nil)
			assert(#a == 0 and a.n == nil)
		end)

		it("test #5", function()
			local a = {10,20,30,40}
			expect(table.remove(a, #a + 1)):eq(nil)
			-- expect.error(table.remove, a, 0)
			expect(a[#a]):eq(40)
			expect(table.remove(a, #a)):eq(40)
			expect(a[#a]):eq(30)
			expect(table.remove(a, 2)):eq(20)
			expect(a[#a]):eq(30)
			expect(#a):eq(2)
		end)
	end)

	describe("table.move :lua>=5.3", function()
		direct_and_proxy("moves forward", function(wrap)
			local tbl = { 10, 20, 30 }
			table.move(wrap(tbl), 1, 3, 2)
			expect(tbl):same { 10, 10, 20, 30 }
		end)

		direct_and_proxy("moves forward with overlap", function(wrap)
			local tbl = { 10, 20, 30 }
			table.move(wrap(tbl), 1, 3, 3)
			expect(tbl):same { 10, 20, 10, 20, 30 }
		end)

		direct_and_proxy("moves forward to new table", function(wrap)
			local tbl = { 10, 20, 30 }
			local new = {}
			table.move(wrap(tbl), 1, 10, 1, wrap(new))
			expect(new):same { 10, 20, 30 }
		end)

		-- We do test this above too, but this is a more explicit test.
		it("uses metamethods", function()
			local a = setmetatable({}, {
				__index = function (_,k) return k * 10 end,
				__newindex = error
			})
			local b = table.move(a, 1, 10, 3, {})
			expect(a):same {}
			expect(b):same { nil,nil,10,20,30,40,50,60,70,80,90,100 }

		  	local b = setmetatable({""}, {
				__index = error,
				__newindex = function (t,k,v) t[1] = string.format("%s(%d,%d)", t[1], k, v) end
			})

			table.move(a, 10, 13, 3, b)
			expect(b[1]):eq "(3,100)(4,110)(5,120)(6,130)"
			expect.error(table.move, b, 10, 13, 3, b):eq(b)
		end)

		it("copes close to overflow", function()
			local a = table.move({[maxI - 2] = 1, [maxI - 1] = 2, [maxI] = 3}, maxI - 2, maxI, -10, {})
			expect(a):same {[-10] = 1, [-9] = 2, [-8] = 3}

			local a = table.move({[minI] = 1, [minI + 1] = 2, [minI + 2] = 3}, minI, minI + 2, -10, {})
			expect(a):same { [-10] = 1, [-9] = 2, [-8] = 3 }

			local a = table.move({45}, 1, 1, maxI)
			expect(a):same { 45, [maxI] = 45 }

			local a = table.move({[maxI] = 100}, maxI, maxI, minI)
			expect(a):same { [minI] = 100, [maxI] = 100 }

			local a = table.move({[minI] = 100}, minI, minI, maxI)
			expect(a):same { [minI] = 100, [maxI] = 100 }
		end)

		it("copes with large numbers", function()
			local function checkmove (f, e, t, x, y)
				local pos1, pos2
				local a = setmetatable({}, {
					__index = function (_,k) pos1 = k end,
					__newindex = function (_,k) pos2 = k; error() end
				})
				local st, msg = pcall(table.move, a, f, e, t)
				expect(st):eq(false)
				expect(msg):eq(nil)
				expect(pos1):eq(x)
				expect(pos2):eq(y)
			end

			checkmove(1, maxI, 0, 1, 0)
			checkmove(0, maxI - 1, 1, maxI - 1, maxI)
			checkmove(minI, -2, -5, -2, maxI - 6)
			checkmove(minI + 1, -1, -2, -1, maxI - 3)
			checkmove(minI, -2, 0, minI, 0)  -- non overlapping
			checkmove(minI + 1, -1, 1, minI + 1, 1)  -- non overlapping
		end)

		it("errors on overflow :lua~=5.4", function()
			expect.error(table.move, {}, 0, maxI, 1):str_match("too many")
			expect.error(table.move, {}, -1, maxI - 1, 1):str_match("too many")
			expect.error(table.move, {}, minI, -1, 1):str_match("too many")
			expect.error(table.move, {}, minI, maxI, 1):str_match("too many")
			expect.error(table.move, {}, 1, maxI, 2):str_match("wrap around")
			expect.error(table.move, {}, 1, 2, maxI):str_match("wrap around")
			expect.error(table.move, {}, minI, -2, 2):str_match("wrap around")
		end)
	end)

	describe("table.sort", function()
		it("behaves identically to PUC Lua on sparse tables", function()
			local test = {[1]="e",[2]="a",[3]="d",[4]="c",[8]="b"}

			table.sort(test, function(a, b)
				if not a then
					return false
				end
				if not b then
					return true
				end
				return a < b
			end)

			expect(test):same { "a", "b", "c", "d", "e" }
		end)

		it("uses metatables :lua>=5.3", function()
			local original = { "e", "d", "c", "b", "a" }
			local slice = make_slice(original, 2, 3)

			table.sort(slice)
			expect(original):same { "e", "b", "c", "d", "a" }
			expect(next(slice)):eq(nil)
		end)
	end)

	describe("table.pack", function()
		it("counts nils :lua>=5.2", function()
			expect(table.pack(1, "foo", nil, nil)):same { n = 4, 1, "foo" }
		end)
	end)

	describe("table.unpack", function()
		it("accepts nil arguments :lua>=5.2", function()
			local a, b, c = table.unpack({ 1, 2, 3, 4, 5 }, nil, 2)
			assert(a == 1)
			assert(b == 2)
			assert(c == nil)

			local a, b, c = table.unpack({ 1, 2 }, nil, nil)
			assert(a == 1)
			assert(b == 2)
			assert(c == nil)
		end)

		it("takes slices of tables :lua>=5.2", function()
			expect(table.pack(table.unpack({ 1, "foo" }))):same { n = 2, 1, "foo" }
			expect(table.pack(table.unpack({ 1, "foo" }, 2))):same { n = 1, "foo" }
			expect(table.pack(table.unpack({ 1, "foo" }, 2, 5))):same { n = 4, "foo" }
		end)

		it("uses metamethods :lua>=5.3", function()
			local basic = make_slice({ "a", "b", "c", "d", "e" }, 2, 3)
			expect(table.pack(table.unpack(basic))):same { n = 3, "b", "c", "d" }
			expect(table.pack(table.unpack(basic, 2))):same { n = 2, "c", "d" }
		end)
	end)

	describe("table.concat", function()
		it("uses metamethods :lua>=5.3", function()
			local basic = make_slice({ "a", "b", "c", "d", "e" }, 2, 3)
			expect(table.concat(basic)):eq("bcd")
		end)
	end)
end)
