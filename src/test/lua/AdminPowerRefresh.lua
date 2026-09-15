local checks, rebuilds = 0, 0
local function check(ok, why) assert(ok, why); checks=checks+1 end
local allowed, value = true, false
isDebugEnabled=function() return false end
local option={id="Invisible",capability="invisible",getValue=function() return value end}
local instance={player={getRole=function() return {hasCapability=function() return allowed end} end}}
local function box() return {setSelected=function(self,i,v) self[i]=v end} end
instance.tickBoxLeft,instance.tickBoxRight=box(),box()
ISAdminPowerUI={OptionList={option},instance=instance,
    updateAdminPower=function(self)
        rebuilds=rebuilds+1; self.optionsLeft=allowed and {option} or {}; self.optionsRight={}
        self.tickBoxLeft[1]=allowed and value or nil
    end,
    onClick=function() end, OnOpenPanel=function() end}
setmetatable(instance,{__index=ISAdminPowerUI})
dofile("src/main/resources/lua/client/ISUI/AdminPanel/StormAdminPowerUIFix.lua")
instance:updateAdminPower()
for i=1,1000 do instance:updateAdminPower() end
check(rebuilds==1,"Unrelated players' repeated RefreshCheats events do not rebuild widgets")
instance:onTicked(1,true,nil,nil,instance.tickBoxLeft)
instance.tickBoxLeft[1]=true
instance:updateAdminPower(); check(instance.tickBoxLeft[1]==true,"Pending edit survives redundant refresh")
value=true; instance:updateAdminPower(); check(rebuilds==2,"Changed live state rebuilds immediately")
allowed=false; instance:updateAdminPower(); check(#instance.optionsLeft==0 and rebuilds==3,"Capability revocation is not delayed")
ISAdminPowerUI.OnOpenPanel(); check(instance.stormPendingTicks==nil and instance.stormPowerState==nil,"Reopen resets pending state and invalidates comparison")
print("PASS: "..checks.." admin-powers refresh checks")
