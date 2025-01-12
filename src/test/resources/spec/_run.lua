--- Run our test suite inside a Lua interpreter. No Lua version actually passes
-- all our tests, but it's a good way to check compliance.

local verbose = false
for _, arg in ipairs(arg) do
	if arg == "--verbose" or arg == "-v" then verbose = true
	else
		io.stderr:write("Unexpected option\n")
		os.exit(2)
	end
end

local version_str = _VERSION:match("Lua (%d.%d)")
if not version_str then error("Cannot find Lua version") end
local version = tonumber(version_str)

local files = {}
local handle = io.popen("git ls-files 'src/test/resources/spec/*_spec.lua'")
for line in handle:lines() do files[#files + 1] = line end
handle:close()

local function check_version(name)
	local version_check = name:match(":lua([^ ]+)")
	if not version_check then return true end

	return (loadstring or load)("return " .. version .. version_check, "=check.lua")()
end

local tests = {}
local path, active = "", true
function describe(name, func)
	if path == nil then error("Cannot define tasks at this time") end
	local old_path, old_active = path, active

	path = path .. name .. " ▸ "
	active = active and check_version(name)
	func()

	path, active = old_path, old_active
end

function pending(name, func)
	if path == nil then error("Cannot define tasks at this time") end
	tests[#tests + 1] = { path = path, name = name, func = false }
end

function it(name, func)
	if path == nil then error("Cannot define tasks at this time") end

	local active = active and check_version(name)
	tests[#tests + 1] = { path = path, name = name, func = active and func }
end

fail = error
dofile("src/test/resources/spec/_prelude.lua")
for _, file in ipairs(files) do dofile(file) end
path = nil

local function log_test(colour, test, status)
	print(("\27[38:5:247m%s\27[38:5:%dm%s \27[38:5:247m(%s)\27[0m"):format(test.path, colour, test.name, status))
end

local total, skipped, failed = 0, 0, 0
for _, test in ipairs(tests) do
	if not test.func then
		skipped = skipped + 1
		if verbose then log_test(184, test, "skipped") end
	else
		total = total + 1

		local co = coroutine.create(test.func)
		local ok, err = coroutine.resume(co)
		if coroutine.status(co) ~= "dead" then
			ok, err = false, "coroutine is not yet dead"
		end
		if not ok then
			failed = failed + 1

			log_test(197, test, "failed")
			print(debug.traceback(co, err))
		else
			if verbose then log_test(70, test, "passed") end
		end
	end
end

print(("\27[1mRan %d tests (%d skipped)\27[0m"):format(total, skipped))
if failed > 0 then os.exit(1) end
