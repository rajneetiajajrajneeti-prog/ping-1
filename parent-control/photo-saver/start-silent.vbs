Set WshShell = CreateObject("WScript.Shell")
WshShell.Run "cmd /c cd /d """ & WScript.Arguments(0) & """ && node index.js >> photo-saver.log 2>&1", 0, False
