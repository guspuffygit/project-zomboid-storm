local StormBootstrapVerification = {}

local SETTINGS_FILE = "StormBootstrapSettings.ini"

local function loadDontShowAgain()
    local file = getFileReader(SETTINGS_FILE, true)
    if not file then return false end
    local line = file:readLine()
    file:close()
    return line == "dontShowAgain=true"
end

local function saveDontShowAgain(tickBox)
    if tickBox and tickBox:isSelected(1) then
        local file = getFileWriter(SETTINGS_FILE, true, false)
        file:write("dontShowAgain=true\r\n")
        file:close()
    end
end

function StormBootstrapVerification.createOSModal()
    local core = getCore();

    local descriptionLines = {
        "OPTIONAL SETUP",
        "To get enhanced Quality of Life features, add this argument to enable the Storm Mod loader.",
        "1. CLICK the button below matching your computer (Windows/Linux/Mac) to COPY.",
        "2. Open Steam Library -> Right Click 'Project Zomboid' -> Select 'Properties...'",
        "3. Stay on the 'General' tab. Look at the very bottom for 'LAUNCH OPTIONS'.",
        "4. PASTE the code inside that text box.",
        "5. Close the properties window and RESTART the game.",
    }

    local description = table.concat(descriptionLines, "\n\n")

    local width, height = ISModalDialog.CalcSize(0, 0, description)
    height = height + 30;
    local x = (core:getScreenWidth() / 2) - (width / 2);
    local y = (core:getScreenHeight() / 2) - (height / 2);

    local windowsCopy = '-agentpath:../../workshop/content/108600/3670772371/mods/storm/bootstrap/agentlib.dll=storm-bootstrap.jar --'
    local linuxCopy = '-javaagent:../../workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar --'
    local macCopy = '-javaagent:../../../../../workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar --'

    local modal = ISModalDialog:new(x, y, width, height, description, false, nil, nil, nil);
    modal:initialise();
    modal:addToUIManager();

    if modal.ok then
        modal:removeChild(modal.ok);
        modal.ok = nil;
    end

    local btnWid = 100;
    local btnHgt = 25;
    local pad = 10;
    local totalBtnWidth = (btnWid * 3) + (pad * 2);
    local startX = (width - totalBtnWidth) / 2;
    local btnY = modal:getHeight() - btnHgt - 10;

    local tickBox = ISTickBox:new(startX, btnY - btnHgt - pad, width - (startX * 2), btnHgt, "", nil, nil);
    tickBox:initialise();
    tickBox:instantiate();
    tickBox:addOption("Don't show this again");
    modal:addChild(tickBox);

    local btnWindows = ISButton:new(startX, btnY, btnWid, btnHgt, "Copy Windows", modal, function(self)
        print('Windows')
        Clipboard.setClipboard(windowsCopy)
        saveDontShowAgain(tickBox)
        self:destroy();
    end);
    btnWindows:initialise();
    modal:addChild(btnWindows);

    local btnLinux = ISButton:new(startX + btnWindows.width + pad, btnY, btnWid, btnHgt, "Copy Linux", modal, function(self)
        print('Linux')
        Clipboard.setClipboard(linuxCopy)
        saveDontShowAgain(tickBox)
        self:destroy();
    end);
    btnLinux:initialise();
    modal:addChild(btnLinux);

    local btnMac = ISButton:new(startX + btnWindows.width + pad + btnLinux.width + pad, btnY, btnWid, btnHgt, "Copy Mac", modal, function(self)
        print('Mac')
        Clipboard.setClipboard(macCopy)
        saveDontShowAgain(tickBox)
        self:destroy();
    end);

    btnMac:initialise();
    modal:addChild(btnMac);

    modal.x = (core:getScreenWidth() / 2) - (modal.x / 2)
    modal.y = (core:getScreenHeight() / 2) - (modal.y / 2)

    return modal;
end

function StormBootstrapVerification.check()
    if Storm then
        print("Storm is loaded successfully")
    else
        print("User does not have Storm mod loader enabled if this method triggers")

        if loadDontShowAgain() then
            return
        end

        local modal = StormBootstrapVerification.createOSModal()

        if JoypadState.players[1] then
            setJoypadFocus(0, modal)
        end
    end
end

return StormBootstrapVerification
