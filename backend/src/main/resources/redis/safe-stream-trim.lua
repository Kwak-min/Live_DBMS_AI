-- KEYS[1] stream
-- ARGV[1] max deletions per call
-- ARGV[2] hard maximum age in ms (0 disables the cap)
-- ARGV[3..] consumer groups that must exist before the 24h safe boundary applies
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
local nowMs = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
local batch = tonumber(ARGV[1])
local maxAgeMs = tonumber(ARGV[2])

-- 1. Safe boundary: at least 24h old, read and acknowledged by every group, all required groups present.
local blocked = false
local safeCutoff = nowMs - 86400000
if safeCutoff <= 0 then
    blocked = true
    safeCutoff = 0
end
local boundary = string.format('%.0f', safeCutoff) .. '-0'
local oldestPending = nil
local present = {}
local groups = redis.call('XINFO', 'GROUPS', stream)
if #groups == 0 then
    blocked = true
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
    if pending[1] > 0 then
        if idLess(pending[2], boundary) then
            boundary = pending[2]
        end
        if oldestPending == nil or idLess(pending[2], oldestPending) then
            oldestPending = pending[2]
        end
    end
end

for i = 3, #ARGV do
    if not present[ARGV[i]] then
        blocked = true
    end
end

local target = nil
if not blocked and boundary ~= '0-0' then
    target = boundary
end

-- 2. Hard cap: records older than maxAgeMs go even when a group is missing, disabled or lagging,
--    but a record still pending in any group is never deleted.
if maxAgeMs > 0 then
    local hardCutoff = nowMs - maxAgeMs
    if hardCutoff > 0 then
        local hard = string.format('%.0f', hardCutoff) .. '-0'
        if oldestPending ~= nil and idLess(oldestPending, hard) then
            hard = oldestPending
        end
        if target == nil or idLess(target, hard) then
            target = hard
        end
    end
end

if target == nil or target == '0-0' then
    return 0
end

-- Bound exact deletion while retaining the boundary record itself.
local candidates = redis.call('XRANGE', stream, '-', '(' .. target, 'COUNT', batch + 1)
if #candidates == 0 then
    return 0
end
if #candidates > batch then
    target = candidates[batch + 1][1]
end
return redis.call('XTRIM', stream, 'MINID', '=', target)
