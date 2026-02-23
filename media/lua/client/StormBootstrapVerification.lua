local function createOSModal()
    local core = getCore();

    local descriptionLines = {
        "                                                           !!! STOP: REQUIRED SETUP !!!",
        "1. CLICK the button below matching your computer (Windows or Linux) to COPY.",
        "2. Open Steam Library -> Right Click 'Project Zomboid' -> Select 'Properties...'",
        "3. Stay on the 'General' tab. Look at the very bottom for 'LAUNCH OPTIONS'.",
        "4. PASTE the code inside that text box.",
        "5. Close the properties window and RESTART the game.",
    }

    local description = table.concat(descriptionLines, "\n\n")

    local width, height = ISModalDialog.CalcSize(0, 0, description)
    local x = (core:getScreenWidth() / 2) - (width / 2);
    local y = (core:getScreenHeight() / 2) - (height / 2);

    local windowsCopy = '-agentpath:../../workshop/content/108600/3670772371/mods/storm/bootstrap/agentlib.dll=storm-bootstrap.jar --'
    local linuxCopy = '-javaagent:../../workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar --'
    local macCopy = '-javaagent:../../../../../workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar --'

    local modal = ISModalDialog:new(x, y, width, height, description, false, nil, nil, nil);
    modal:initialise();
    modal:addToUIManager();

    if modal.ok then
        modal.ok:setVisible(false);
    end

    local btnWid = 100;
    local btnHgt = 25;
    local pad = 10;
    local totalBtnWidth = (btnWid * 3) + (pad * 2);
    local startX = (width - totalBtnWidth) / 2;
    local btnY = modal:getHeight() - btnHgt - 10;

    local btnWindows = ISButton:new(startX, btnY, btnWid, btnHgt, "Copy Windows", modal, function(self)
        print('Windows')
        Clipboard.setClipboard(windowsCopy)
        self:destroy();
    end);
    btnWindows:initialise();
    modal:addChild(btnWindows);

    local btnLinux = ISButton:new(startX + btnWindows.width + pad, btnY, btnWid, btnHgt, "Copy Linux", modal, function(self)
        print('Linux')
        Clipboard.setClipboard(linuxCopy)
        self:destroy();
    end);
    btnLinux:initialise();
    modal:addChild(btnLinux);

    local btnMac = ISButton:new(startX + btnWindows.width + pad + btnLinux.width + pad, btnY, btnWid, btnHgt, "Copy Mac", modal, function(self)
        print('Mac')
        Clipboard.setClipboard(macCopy)
        self:destroy();
    end);

    btnMac:initialise();
    modal:addChild(btnMac);

    modal.x = (core:getScreenWidth() / 2) - (modal.x / 2)
    modal.y = (core:getScreenHeight() / 2) - (modal.y / 2)

    return modal;
end

function stormLoaderVerificationCheck()
    if Storm then
        print("Storm is loaded successfully")
    else
        print("User does not have Storm mod loader enabled if this method triggers")
        local modal = createOSModal()

        modal:initialise()
        modal:addToUIManager()

        if JoypadState.players[1] then
            setJoypadFocus(0, modal)
        end
    end
end

Events.OnGameStart.Add(stormLoaderVerificationCheck)
