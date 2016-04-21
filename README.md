
iTired is an Android app interfaced with BITalino biosensor kit to detect localized muscle fatigue during isometric exercises.
INTRODUCTION

This is a readme file explaining how to setup, build, and launch the application titled iTired including hardware setup requirements. iTired is an Android app interfaced with BITalino biosensor kit to detect localized muscle fatigue during isometric exercises. 

PRE-REQUISITES

Hardware Requirements

	Following hardware accessories are required in order to setup and system.

Hardware Name	Unit
BITalino Kit	1
Android Mobile	1
USB Cable	1
Electrodes	1
Weight of 5 lb	1
Sticky pads	Minimum 3

Software Requirements

Following software versions are used 

Software Name	Version
Eclipse IDE for Java Developers
	Version: Mars.1 Release (4.5.1)
Build id: 20150924-1200
Android SDK Tools	24.4.1
Android SDK Platform-tools
	23.1
Android SDK Build-tools	23.0.2
Android 4.0.3	API 15
Android Support Library	23.2.1
Google Repository	24
Google USB Driver	11

1.	Your system should have Eclipse IDE installed. If not, download and install Eclipse IDE for Java Developers from the following link http://www.eclipse.org/downloads/packages/eclipse-ide-java-developers/mars2

2.	Check if you have Android SDK installed. You can look for AVD Manager.exe which generally indicates you have Android SDK. If not, download the latest Android SDK from the following link http://developer.android.com/sdk/index.html#Other and choose the windows platform. 
The Android SDK archive initially contains only the basic SDK tools. It does not contain an Android platform or any third-party libraries. In fact, it doesn't even have all the tools you need to develop an application. In order to start developing applications, you must install the Platform-tools and at least one version of the Android platform, using the SDK Manager. Platform-tools contains build tools that are periodically updated to support new features in the Android platform (which is why they are separate from basic
SDK tools), including adb, dexdump, and others. 


3.	To start the SDK Manager, please execute the program "SDK Manager.exe".
4.	Under Tools option, select the following with latest Rev.
−	Android SDK Tools
−	Android SDK Platform-tools
−	Android SDK Build-tools

5.	Select Android 4.0.3(API 15)
6.	Under Extra option, select the following
−	Android Support Library
−	Google Repository
−	Google USB Driver
7.	Install packages by clicking on Install packages and close the window.
8.	Download the project iTired from Github link from location: 
You may need to have an access to project. Please ask the concerned administrator.


IMPORTING THE PROJECT INTO ECLIPSE WORKSPACE

1.	Start Eclipse, and select File->Import option.
2.	Under “General”, select “Existing Projects into Workspace”. 
3.	Browse to the project directory “iTired” in your computer and click “Finish”.

BUILDING and RUNNING APPLICATION 

1.	Connect Android mobile device to your computer using USB cable. You may run the emulator instead of the actual device.
2.	In Eclipse iTired project, right click on it and select Run as -> Android Application. 
3.	Choose the running Android device from the Android Device Chooser. 
See the application running on mobile device with “Paired Devices” form opened.

SYSTEM SETUP

1.	Connect three surface electrodes to participant’s hand as shown in below figure. These pre-jelled electrodes are of 10 mm diameter, placed more than 10 mm apart to avoid crosstalk between adjacent muscles. 
2.	Ask participant to hold the weight of 5 pound parallel to horizontal axis.
3.	Switch on the BITalino kit by toggling the power switch.
4.	Launch the “iTired” application from the mobile device. 
5.	Select BITalino option from the paired devices list.
6.	Select Patient name option.
7.	Enter Patient’s first and last name.
8.	Select “Start Session” option.
9.	Select start recording option as shown in below figure.
10.	Participant shall hold the weight until he/she feels exhausted.
