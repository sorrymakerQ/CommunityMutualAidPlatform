@echo off
REM ============================================================
REM  Alipay SANDBOX refund tool
REM
REM  Usage:
REM    refund.cmd <out_trade_no>              query trade status only
REM    refund.cmd <out_trade_no> 0.01         partial refund 0.01 CNY
REM    refund.cmd <out_trade_no> full         full refund
REM    refund.cmd <out_trade_no> full MY-001  custom out_request_no (idempotency test)
REM
REM  out_trade_no == tb_pay_order.pay_no
REM  Config is read from backend/src/main/resources/application.yml
REM ============================================================
setlocal

set "DIR=%~dp0"
set "REPO=D:\develop\apache-maven-3.8.8\mvn_repo"
set "YML=%DIR%..\..\backend\src\main\resources\application.yml"

set "CP=%REPO%\com\alipay\sdk\alipay-sdk-java\4.16.2.ALL\alipay-sdk-java-4.16.2.ALL.jar"
set "CP=%CP%;%REPO%\commons-logging\commons-logging\1.1.1\commons-logging-1.1.1.jar"
set "CP=%CP%;%REPO%\com\alibaba\fastjson\1.2.50\fastjson-1.2.50.jar"
set "CP=%CP%;%REPO%\org\bouncycastle\bcprov-jdk15on\1.62\bcprov-jdk15on-1.62.jar"
set "CP=%CP%;%REPO%\dom4j\dom4j\1.6.1\dom4j-1.6.1.jar"

if "%~1"=="" (
  echo Usage:
  echo   refund.cmd ^<out_trade_no^>              query trade status only
  echo   refund.cmd ^<out_trade_no^> 0.01         partial refund
  echo   refund.cmd ^<out_trade_no^> full         full refund
  echo   refund.cmd ^<out_trade_no^> full MY-001  custom out_request_no
  exit /b 1
)

if not exist "%DIR%AlipayRefundProbe.class" (
  echo [build] compiling AlipayRefundProbe.java ...
  javac -encoding UTF-8 -cp "%CP%" -d "%DIR%" "%DIR%AlipayRefundProbe.java"
  if errorlevel 1 (
    echo [build] compile FAILED
    exit /b 1
  )
)

chcp 65001 >nul
java -Dfile.encoding=UTF-8 -cp "%DIR%;%CP%" AlipayRefundProbe "%YML%" %*
set "RC=%ERRORLEVEL%"
chcp 936 >nul

endlocal & exit /b %RC%
