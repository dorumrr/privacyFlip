package io.github.dorumrr.privacyflip.privilege

/**
 * Represents the method used to execute privileged commands
 */
enum class PrivilegeMethod {
    /**
     * No privilege available - app cannot function
     */
    NONE,
    
    /**
     * Traditional root access via Magisk/SuperSU (UID 0)
     */
    ROOT,
    
    /**
     * Shizuku with ADB privileges (UID 2000)
     * Requires Shizuku app and wireless debugging or PC connection
     */
    SHIZUKU,

    /**
     * Dhizuku with Device Owner privileges
     * Requires Dhizuku app and Device Owner setup
     * More stable than Shizuku, survives reboots
     */
    DHIZUKU,

    /**
     * Sui - Magisk module that provides Shizuku API with root privileges (UID 0)
     * Best of both worlds: automatic like root, uses Shizuku API
     */
    SUI;
    
    /**
     * Returns true if this method provides root-level privileges (UID 0)
     */
    fun isRootLevel(): Boolean = this == ROOT || this == SUI
    
    /**
     * Returns true if this method provides ADB-level privileges (UID 2000)
     */
    fun isAdbLevel(): Boolean = this == SHIZUKU

    /**
     * Returns true if this method provides Device Owner privileges
     */
    fun isDeviceOwnerLevel(): Boolean = this == DHIZUKU
    
    /**
     * Returns true if any privilege is available
     */
    fun isAvailable(): Boolean = this != NONE
    
    /**
     * Returns user-friendly name for this privilege method
     */
    fun getDisplayName(): String = when (this) {
        NONE -> "No Privilege"
        ROOT -> "Root (Magisk/SuperSU)"
        SHIZUKU -> "Shizuku (ADB)"
        DHIZUKU -> "Dhizuku (Device Owner)"
        SUI -> "Sui (Magisk Module)"
    }
    
    /**
     * Returns short description of this privilege method
     */
    fun getDescription(): String = when (this) {
        NONE -> "Root, Dhizuku, or Shizuku required"
        ROOT -> "Full root access via Magisk or SuperSU"
        SHIZUKU -> "ADB privileges via Shizuku app"
        DHIZUKU -> "Device Owner privileges via Dhizuku app"
        SUI -> "Root privileges via Sui Magisk module"
    }
}

