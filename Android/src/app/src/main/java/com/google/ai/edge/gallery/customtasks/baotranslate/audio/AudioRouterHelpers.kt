package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Build
import com.google.ai.edge.gallery.R

internal fun isWiredOutput(device: AudioDeviceInfo): Boolean =
  DeviceProbe.isWiredOutput(device.type)

internal fun isBleOutput(device: AudioDeviceInfo): Boolean =
  DeviceProbe.isBleOutput(device.type)

internal fun isBluetoothOutput(device: AudioDeviceInfo): Boolean =
  DeviceProbe.isBluetoothOutput(device.type)

internal fun isSelectableBluetoothOutput(device: AudioDeviceInfo): Boolean =
  isBleOutput(device) ||
    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
    isSelectableScoOutput(device)

internal fun isSelectableScoOutput(device: AudioDeviceInfo): Boolean =
  DeviceProbe.isSelectableScoOutput(
    DeviceDescriptor.from(device),
    localModel = Build.MODEL,
    localDevice = Build.DEVICE,
  )

internal fun isSelectedDeviceAvailable(selected: AudioDevice, available: List<AudioDevice>): Boolean =
  available.any { device ->
    when {
      selected is AudioDevice.Speaker && device is AudioDevice.Speaker -> true
      selected is AudioDevice.BluetoothHeadset && device is AudioDevice.BluetoothHeadset ->
        device.name == selected.name && device.transport == selected.transport
      selected is AudioDevice.WiredHeadset && device is AudioDevice.WiredHeadset -> device.name == selected.name
      else -> false
    }
  }
internal fun productNameOrFallback(context: Context, info: AudioDeviceInfo, fallbackRes: Int): String {
  val productName = info.productName?.toString()
  if (!productName.isNullOrBlank() && productName != "null") return productName
  val address = info.address
  if (!address.isNullOrBlank() && address != "00:00:00:00:00:00") return address
  return context.getString(fallbackRes)
}

internal fun bluetoothDeviceName(context: Context, info: AudioDeviceInfo): String =
  productNameOrFallback(context, info, R.string.bao_translate_audio_device_bluetooth)

internal fun bluetoothInputName(
  context: Context,
  info: AudioDeviceInfo,
  outputDevices: Array<out AudioDeviceInfo>,
): String? {
  if (!isBluetoothOutput(info)) return null
  if (info.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO || !isPlaceholderBluetoothEndpoint(info)) {
    return bluetoothDeviceName(context, info)
  }
  return outputDevices.firstOrNull {
    (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) &&
      !isPlaceholderBluetoothEndpoint(it)
  }?.let { bluetoothDeviceName(context, it) }
}

internal fun isPlaceholderBluetoothEndpoint(info: AudioDeviceInfo): Boolean {
  return DeviceProbe.isPlaceholderBluetoothEndpoint(
    DeviceDescriptor.from(info),
    localModel = Build.MODEL,
    localDevice = Build.DEVICE,
  )
}
