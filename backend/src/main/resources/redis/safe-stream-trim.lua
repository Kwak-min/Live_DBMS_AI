local stream = KEYS[1]
if redis.call('EXISTS', stream) == 0 then
    return 0
end

-- Compare decimal strings: Redis IDs can exceed Lua's exact integer range.
local function decimalLess(a, b)
    if #a ~= #b then
        return #a < #b
    end
    return a < b
end

local function idLess(a, b)
    local am, as = string.match(a, '^(%d+)%-(%d+)$')
    local bm, bs = string.match(b, '^(%d+)%-(%d+)$')
    if am ~= bm then
        return decimalLess(am, bm)
    end
    return decimalLess(as, bs)
end

local now = redis.call('TIME')
local cutoff = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) - 86400000
if cutoff <= 0 then
    return 0
end
local boundary = string.format('%.0f', cutoff) .. '-0'
local present = {}
local groups = redis.call('XINFO', 'GROUPS', stream)
if #groups == 0 then
    return 0
end

for _, fields in ipairs(groups) do
    local name
    local delivered
    for i = 1, #fields, 2 do
        if fields[i] == 'name' then
            name = fields[i + 1]
        elseif fields[i] == 'last-delivered-id' then
            delivered = fields[i + 1]
        end
    end
    if not name or not delivered then
        return 0
    end
    present[name] = true
    if idLess(delivered, boundary) then
        boundary = delivered
    end
    local pending = redis.call('XPENDING', stream, name)
    if pending[1] > 0 and idLess(pending[2], boundary) then
        boundary = pending[2]
    end
end

for i = 2, #ARGV do
    if not present[ARGV[i]] then
        return 0
    end
end

if boundary == "0-0" then
    return 0
end

-- Bound exact deletion while retaining the boundary record itself.
local batch = tonumber(ARGV[1])
local candidates = redis.call('XRANGE', stream, '-', '(' .. boundary, 'COUNT', batch + 1)
if #candidates > batch then
    boundary = candidates[batch + 1][1]
end
return redis.call('XTRIM', stream, 'MINID', '=', boundary)
