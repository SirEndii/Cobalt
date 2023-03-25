describe("Coroutines", function()
	describe("coroutine.running", function()
		it("returns false if we're the not the main coroutine :lua>=5.2", function()
			local co = coroutine.create(function() return coroutine.running() end)
			local _, running, is_main = assert(coroutine.resume(co))
			expect(running):eq(co)
			expect(is_main):eq(false)
		end)
	end)

	describe("coroutine.isyieldable", function()
		it("returns true inside a coroutine :lua>=5.3", function()
			local co = coroutine.create(function() return coroutine.isyieldable() end)
			local _, isyieldable = assert(coroutine.resume(co))
			expect(isyieldable):eq(true)
		end)
	end)
end)
