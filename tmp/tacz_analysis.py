#!/usr/bin/env python3
"""简单分析 TACZ 类结构"""
import zipfile
import struct

def read_u1(data, offset):
    return struct.unpack_from('B', data, offset)[0]

def read_u2(data, offset):
    return struct.unpack_from('>H', data, offset)[0]

def read_u4(data, offset):
    return struct.unpack_from('>I', data, offset)[0]

def read_utf8(data, offset, limit):
    length = read_u2(data, offset)
    offset += 2
    return data[offset:offset+length].decode('utf-8', errors='replace')

def analyze_class(jar_path, class_path):
    with zipfile.ZipFile(jar_path) as zf:
        class_name = class_path.replace('/', '.') + '.class'
        data = zf.read(class_name)
    
    cp_count = read_u2(data, 8)
    cp_offset = 10
    constant_pool = []
    
    for i in range(1, cp_count):
        constant_pool.append(cp_offset)
        cp_type = read_u1(data, cp_offset)
        if cp_type in (1, 3, 16, 17, 18):
            cp_offset += 2
        elif cp_type in (5, 6):
            cp_offset += 4
        elif cp_type == 7:
            cp_offset += 2
        elif cp_type in (9, 10):
            cp_offset += 4
        elif cp_type == 11:
            cp_offset += 2
        elif cp_type == 15:
            cp_offset += 4
        else:
            break
    
    this_class_idx = read_u2(data, cp_offset)
    cp_offset += 2
    super_class_idx = read_u2(data, cp_offset)
    cp_offset += 2
    
    this_class = read_utf8(data, constant_pool[this_class_idx-1], len(data))
    super_class = read_utf8(data, constant_pool[super_class_idx-1], len(data)) if super_class_idx > 0 else "null"
    
    print(f"=== {class_path} ===")
    print(f"  Super: {super_class}")
    
    interface_count = read_u2(data, cp_offset)
    cp_offset += 2 + interface_count * 2
    
    field_count = read_u2(data, cp_offset)
    cp_offset += 2
    if field_count > 0:
        print(f"  Fields ({field_count}):")
        for _ in range(field_count):
            field_flags = read_u2(data, cp_offset)
            cp_offset += 2
            name_idx = read_u2(data, cp_offset)
            cp_offset += 2
            desc_idx = read_u2(data, cp_offset)
            cp_offset += 2
            
            name = read_utf8(data, constant_pool[name_idx-1], len(data))
            desc = read_utf8(data, constant_pool[desc_idx-1], len(data))
            print(f"    {name}: {desc}")
    
    method_count = read_u2(data, cp_offset)
    cp_offset += 2
    if method_count > 0:
        print(f"  Methods ({method_count}):")
        for _ in range(min(method_count, 10)):  # 只显示前 10 个
            method_flags = read_u2(data, cp_offset)
            cp_offset += 2
            name_idx = read_u2(data, cp_offset)
            cp_offset += 2
            desc_idx = read_u2(data, cp_offset)
            cp_offset += 2
            
            name = read_utf8(data, constant_pool[name_idx-1], len(data))
            desc = read_utf8(data, constant_pool[desc_idx-1], len(data))
            print(f"    {name}{desc}")
    
    print()

if __name__ == '__main__':
    jar_path = 'C:/Users/97128/Documents/GitHub/Enchanted Outlines/run/mods/tacz-neoforge-1.21.1-1.1.8-hotfix-r6.jar'
    
    classes = [
        'com/tacz/guns/client/renderer/item/GunItemRendererWrapper',
        'com/tacz/guns/client/model/BedrockGunModel',
        'com/tacz/guns/client/renderer/item/AnimateGeoItemRenderer',
        'com/tacz/guns/api/client/other/KeepingItemRenderer',
    ]
    
    for class_path in classes:
        try:
            analyze_class(jar_path, class_path)
        except Exception as e:
            print(f"Error analyzing {class_path}: {e}\n")
