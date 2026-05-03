require "PersistedTable"

local MODULE = "StormDiagnostics"
local COMMAND = "ramAlloc"
local SETTINGS_FILE = "StormRamAllocationSettings.txt"
local LOW_RAM_THRESHOLD_GIB = 4
local RECOMMENDED_ARG = "-Xmx8g --"

local function loadDontShowAgain()
    local settings = PersistedTable:read(SETTINGS_FILE)
    return settings.dontShowAgain == "true"
end

local function saveDontShowAgain(tickBox)
    if tickBox and tickBox:isSelected(1) then
        local settings = PersistedTable:read(SETTINGS_FILE)
        settings.dontShowAgain = true
        PersistedTable:save(SETTINGS_FILE, settings)
    end
end

local function showLowRamModal(maxMb, maxGiB)
    local descriptionLines = {
        "LOW RAM ALLOCATION DETECTED",
        string.format("Your game has only allocated %.1f GiB of RAM (max=%d MB).", maxGiB, maxMb),
        string.format("We recommend allocating %d GiB to reduce lag.", LOW_RAM_THRESHOLD_GIB * 2),
        "1. CLICK the button below to COPY the launch argument.",
        "2. Open Steam Library -> Right Click 'Project Zomboid' -> 'Properties...'",
        "3. Stay on the 'General' tab. Look at the bottom for 'LAUNCH OPTIONS'.",
        "4. PASTE the argument inside that text box (append it after anything already there).",
        "5. Close Properties and RESTART the game.",
    }
    local description = table.concat(descriptionLines, "\n\n")

    local core = getCore()
    local width, height = ISModalDialog.CalcSize(0, 0, description)
    height = height + 100
    local x = (core:getScreenWidth() / 2) - (width / 2)
    local y = (core:getScreenHeight() / 2) - (height / 2)

    local modal = ISModalDialog:new(x, y, width, height, description, false, nil, nil, nil)
    modal:initialise()
    modal:addToUIManager()

    if modal.ok then
        modal:removeChild(modal.ok)
        modal.ok = nil
    end

    local btnWid, btnHgt, pad = 160, 25, 10
    local btnX = (width - btnWid) / 2
    local btnCopyY = modal:getHeight() - btnHgt - pad - btnHgt - pad - btnHgt - pad

    local tickBox
    local btnCopy = ISButton:new(btnX, btnCopyY, btnWid, btnHgt, "Copy " .. RECOMMENDED_ARG, modal, function(self)
        Clipboard.setClipboard(RECOMMENDED_ARG)
        self:setTitle("Copied!")
    end)
    btnCopy:initialise()
    modal:addChild(btnCopy)

    tickBox = ISTickBox:new(btnX, btnCopyY + btnHgt + pad, width - (btnX * 2), btnHgt, "", nil, nil)
    tickBox:initialise()
    tickBox:instantiate()
    tickBox:addOption("Don't show this again")
    modal:addChild(tickBox)

    local closeBtnWid = 80
    local closeBtnX = (width - closeBtnWid) / 2
    local btnClose = ISButton:new(closeBtnX, btnCopyY + btnHgt + pad + btnHgt + pad, closeBtnWid, btnHgt, "Close", modal, function(self)
        saveDontShowAgain(tickBox)
        modal:destroy()
    end)
    btnClose:initialise()
    modal:addChild(btnClose)

    if JoypadState.players[1] then
        setJoypadFocus(0, modal)
    end
end

local function onTick()
    Events.OnTick.Remove(onTick)

    local perf = getPerformanceLocal()
    if not perf then
        return
    end

    local maxMb = perf["memory-max"] or 0
    sendClientCommand(MODULE, COMMAND, {
        maxMb = maxMb,
        totalMb = perf["memory-total"] or 0,
        usedMb = perf["memory-used"] or 0,
        freeMb = perf["memory-free"] or 0,
    })

    local maxGiB = maxMb / 1073
    if maxGiB < LOW_RAM_THRESHOLD_GIB and not loadDontShowAgain() then
        showLowRamModal(maxMb, maxGiB)
    end
end

Events.OnTick.Add(onTick)
