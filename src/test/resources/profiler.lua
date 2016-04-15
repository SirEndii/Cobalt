local function memorise(func)
	local cache = {}
	return function(n)
		local val = cache[n]
		if val ~= nil then return val end
		val = func(n)
		cache[n] = val
		return val
	end
end

local function fib(n)
	if n == 0 or n == 1 then
		return 1
	else
		for _ = 0, 1e7 do end -- Takes ~0.5 seconds
		return fib(n - 1) + fib(n - 2)
	end
end
fib = memorise(fib)

local function allFibs(n)
	if n == 0 then
		return fib(n)
	else
		return fib(n), allFibs(n - 1)
	end
end

profiler.start()
allFibs(10)
local res = profiler.stop()
local summary = {}
for _, v in ipairs(res) do
	local name = v.source .. ":" .. v.name
	if v.linedefined and v.lastlinedefined then
		if v.linedefined == v.lastlinedefined then
			name = name .. ":" .. v.linedefined
		else
			name = name .. ":" .. v.linedefined .. "-" .. v.lastlinedefined
		end
	end

	local data = summary[name]
	if data then
		data.calls = data.calls + 1
		data.total = data.total + v.localtime
		data.longest = math.max(data.longest, v.localtime)
		data.longestt = math.max(data.longestt, v.totaltime)
	else
		summary[name] = {
			calls = 1,
			total = v.localtime,
			name = name,
			longest = v.localtime,
			longestt = v.totaltime
		}
	end
end

for _, v in pairs(summary) do
	print(v.name, "Called:" .. v.calls, "Time: " .. v.total, "Average:" .. v.total / v.calls)
	print("", "Longest:" .. v.longest, "Longest (inclusive):" .. v.longestt)
end