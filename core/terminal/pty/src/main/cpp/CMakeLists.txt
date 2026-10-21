cmake_minimum_required(VERSION 3.22.1)

project(kernpty C)

add_library(kernpty SHARED
        pty.c)

find_library(log-lib log)
target_link_libraries(kernpty ${log-lib})
