# Smart Elderly Fall Detection Portal — Mid Review Notes

## Problem and objective
Elderly people can be at risk when a fall happens without anyone nearby. This portal monitors an elderly resident, verifies a potential fall, records the incident, and helps a caregiver respond quickly. The objective is a simple working flow: **React → Spring Boot → MongoDB**.

## Architecture and data flow
`React upload → Spring Boot /api/detection/jobs → Python YOLO Pose → fall_detector.py → POST /api/detection/fall → Spring Boot → MongoDB`

After confirmation, Spring Boot creates an incident report, calls the configured Groq/Qwen workflow, and sends a caregiver notification when email is configured. React uses Axios to call Spring Boot and display the MongoDB incidents.

## Important parts
- **React** presents the home, login, dashboard, monitoring, incidents, reports, and settings screens.
- **Spring Boot** exposes simple REST APIs and coordinates database, report, AI, and email work.
- **MongoDB** stores one flexible document per fall incident in `smart_elderly_portal.incidents`.
- **YOLO Pose** gives 17 body keypoints per person. `fall_detector.py` turns them into explainable features (torso, leg and thigh angles, box shape) and classifies posture: standing, walking, sitting, bending, lying or on the floor.
- **Potential fall**: an upright person reaches the floor within 2.5 s with a large hip drop or body-box collapse. Sitting down, bending or slowly lying back do not trigger it.
- **Verification** watches the same tracked person for the configured window (default five seconds). Getting up cancels it, staying down confirms it, and a video that ends early or a hidden person gives *inconclusive*, never a false confirmation.
- **Qwen via Groq** is used only after confirmation to write a factual summary and caregiver recommendation. It is not used for continuous video detection.
- **PDF and email** are post-confirmation outputs; missing credentials never stop incident storage.

## Viva questions
**What is the purpose?** It improves elderly safety by detecting potential falls, verifying them, and helping caregivers respond quickly.

**Why YOLO?** YOLO Pose is designed for real-time visual detection and supplies pose keypoints for the posture rule.

**Why Qwen?** Qwen produces post-incident text: a short factual summary and practical caregiver recommendation.

**Why not Qwen for detection?** Qwen is a language model; YOLO is suitable for continuous computer vision.

**Why Spring Boot?** It provides REST APIs and connects the frontend, detector, MongoDB, report, and notification features.

**Why MongoDB?** An incident document naturally stores flexible fields such as confidence, timestamps, AI text, and report paths.
