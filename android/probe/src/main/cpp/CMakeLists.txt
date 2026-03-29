cmake_minimum_required(VERSION 3.22.1)

project(iodine_probe)

add_library(iodine_probe SHARED icmp_probe.c)

find_library(log-lib log)

target_link_libraries(iodine_probe ${log-lib})
