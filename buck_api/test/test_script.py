#!/usr/bin/env python3
# Copyright (c) Facebook, Inc. and its affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import argparse
import hashlib
import os
import shutil
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


parser = argparse.ArgumentParser()


def run_build(remain_args):
    parser = argparse.ArgumentParser()
    parser.add_argument("target")
    parser.add_argument("--show-output", action="store_true")
    args = parser.parse_args(remain_args)
    show_output = "--show-output" in args.target or args.show_output
    if "--show-output" in args.target:
        target = args.target.strip("--show-output").strip(" ")
    else:
        target = args.target
    clean_target = Path(target.replace(":", "/").strip("/"))
    # creating a path to a buck-out directory in a temp directory
    conf_dir = hashlib.md5(str(target).encode()).hexdigest()
    output = Path("buck-out", "gen") / Path(conf_dir) / clean_target
    os.makedirs(output.parent)
    with open(output, "w") as f1:
        f1.write("Hello, World!")
    if os.environ["NO_BUCKD"] != "1":
        buckd_sock = Path(".buckd/sock")
        os.makedirs(buckd_sock.parent, exist_ok=True)
        with open(buckd_sock, "w") as sock:
            sock.write("buck daemon exists")
    print_target_to_build_location(show_output, clean_target, target, output)
    print(os.environ["BUCK_BUILD_ID"])
    if "..." not in str(clean_target):
        with open(clean_target, "r") as target_file:
            sys.exit(int(target_file.readlines()[-1]))


def run_run(remain_args):
    parser = argparse.ArgumentParser()
    parser.add_argument("target")
    args = parser.parse_args(remain_args)
    clean_target = Path(args.target.replace(":", "/").strip("/"))
    assert "..." not in str(clean_target)
    # creating a path to a buck-out directory in a temp directory
    conf_dir = hashlib.md5(str(args.target).encode()).hexdigest()
    output = Path("buck-out", "gen") / Path(conf_dir) / clean_target
    os.makedirs(output.parent)
    with open(output, "w") as f1:
        f1.write("Hello, World!")
    if os.environ["NO_BUCKD"] != "1":
        buckd_sock = Path(".buckd/sock")
        os.makedirs(buckd_sock.parent, exist_ok=True)
        with open(buckd_sock, "w") as sock:
            sock.write("buck daemon exists")
    print("run", clean_target)
    print(os.environ["BUCK_BUILD_ID"])


def print_target_to_build_location(show_output, clean_target, target, output):
    if show_output:
        # if target is a folder walk through the folder and create a file for each folder
        if "..." in str(clean_target):
            format_target_folder()
        else:
            print(target + " " + str(output))
    else:
        print(target)


def format_target_folder():
    targets = list(os.walk("targets"))[0][2]
    for t in targets:
        temp_target = "//targets:" + t
        clean_target = Path(temp_target.replace(":", "/").strip("/"))
        conf_dir = hashlib.md5(str(clean_target).encode()).hexdigest()
        output = Path("buck-out", "gen") / Path(conf_dir) / clean_target
        print(f"{temp_target} {str(output)}")


def run_clean(remain_args):
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--keep-cache", action="store_true")
    args = parser.parse_args(remain_args)
    if not args.dry_run:
        shutil.rmtree("buck-out/")
    print(os.environ["BUCK_BUILD_ID"])


def run_kill(remain_args):
    shutil.rmtree(".buckd")
    print(os.environ["BUCK_BUILD_ID"])


def run_test(remain_args):
    parser = argparse.ArgumentParser()
    parser.add_argument("target")
    parser.add_argument("xml")
    args = parser.parse_args(remain_args)
    target = Path(args.target.replace(":", "/").strip("/"))
    conf_dir = hashlib.md5(args.target.encode()).hexdigest()
    output = Path("buck-out", "gen") / Path(conf_dir) / target
    # creating a path to a buck-out directory in a temp directory
    conf_dir = hashlib.md5(args.target.encode()).hexdigest()
    output = Path("buck-out", "gen") / Path(conf_dir) / target
    os.makedirs(output.parent, exist_ok=True)
    with open(output, "w") as f1:
        f1.write("Hello, World!")
    data = None
    with open(target, "r") as target_file:
        data = target_file.readlines()
    target_name = data[0].rstrip("\n")
    status = data[1].rstrip("\n")
    result_type = data[2].rstrip("\n")
    exitcode = int(data[3])
    print(args)
    print(os.environ["BUCK_BUILD_ID"])
    if exitcode != 1:
        # creating xml file structure
        data = ET.Element("tests")
        test = ET.SubElement(data, "test")
        test1 = ET.SubElement(test, "testresult")
        test1.set("name", "test1")
        test.set("name", target_name)
        test1.set("status", status)
        test1.set("type", result_type)
        test_output_xml = ET.tostring(data).decode("utf-8")
        # creating a path to a xml file for test output
        test_output_file = Path(args.xml.replace("--xml ", ""))
        os.makedirs(test_output_file.parent, exist_ok=True)
        with open(test_output_file, "w") as f2:
            f2.write(test_output_xml)
        print(test_output_xml)
    with open(target, "r") as target_file:
        sys.exit(exitcode)


FUNCTION_MAP = {
    "build": run_build,
    "clean": run_clean,
    "kill": run_kill,
    "run": run_run,
    "test": run_test,
}

parser.add_argument("command", choices=FUNCTION_MAP.keys())
(args, remain_args) = parser.parse_known_args()

func = FUNCTION_MAP[args.command]

func(remain_args)
