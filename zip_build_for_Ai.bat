@echo off
title Claude AI Project Zipper
echo ====================================================
echo      Claude AI এর জন্য প্রোজেক্ট জিপ করা হচ্ছে...
echo ====================================================

:: পুরোনো জিপ ফাইল থাকলে সেটা ডিলিট করা
if exist claude_project.zip del /f /q claude_project.zip

:: সাময়িক কাজের জন্য একটি টেম্পোরারি ফোল্ডার তৈরি
if exist claude_temp rmdir /s /q claude_temp
mkdir claude_temp

:: 'build' ফোল্ডার বাদ দেওয়ার জন্য অটোমেটিকভাবে একটি বাদ দেওয়ার লিস্ট তৈরি করা
echo \build\ > exclude_list.txt
echo \.gradle\ >> exclude_list.txt
echo \.idea\ >> exclude_list.txt

:: ১. শুধুমাত্র প্রয়োজনীয় ফাইল ও ফোল্ডারগুলো কপি করা
echo ফাইল কপি করা হচ্ছে...

:: 'app' ফোল্ডার কপি (কিন্তু ভেতরের build ফোল্ডার বাদ দিয়ে)
xcopy "app" "claude_temp\app" /E /I /H /Y /Exclude:exclude_list.txt >nul 2>&1

:: রুট লেভেলের প্রয়োজনীয় গ্র্যাডল ও প্ল্যান ফাইল কপি
copy "build.gradle.kts" "claude_temp\" >nul
copy "gradle.properties" "claude_temp\" >nul
copy "settings.gradle.kts" "claude_temp\" >nul

:: ২. টেম্পোরারি ফোল্ডারটিকে জিপ ফাইলে রূপান্তর করা
echo জিপ ফাইল তৈরি হচ্ছে...
tar -a -c -f claude_project.zip -C claude_temp .

:: ৩. কাজ শেষে টেম্পোরারি ফোল্ডার এবং এক্সক্লুড লিস্ট ডিলিট করে ক্লিন করা
rmdir /s /q claude_temp
if exist exclude_list.txt del /f /q exclude_list.txt

echo ----------------------------------------------------
echo  সফলভাবে 'claude_project.zip' তৈরি হয়েছে!
echo ----------------------------------------------------
pause