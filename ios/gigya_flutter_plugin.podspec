#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint gigya_flutter_plugin.podspec' to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'gigya_flutter_plugin'
  s.version          = '0.1.0'
  s.summary          = 'SAP Gigya Flutter plugin'
  s.description      = <<-DESC
SAP Gigya Flutter plugin
                       DESC
  s.homepage         = 'http://example.com'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Your Company' => 'email@example.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.dependency 'Flutter'
  s.dependency 'Gigya', '>= 1.4.0'
  s.dependency 'GigyaAuth', '>= 1.1.1'
  s.platform = :ios, '11.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'
end
