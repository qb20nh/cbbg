from PIL import Image
import os

def create_animation():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    banded_path = os.path.join(script_dir, 'banded.png')
    dithered_path = os.path.join(script_dir, 'dithered.png')
    output_path = os.path.join(script_dir, 'animated.gif')

    if not os.path.exists(banded_path) or not os.path.exists(dithered_path):
        print(f"Error: Could not find banded.png or dithered.png in {script_dir}")
        return

    img_banded = Image.open(banded_path).convert('RGB')
    img_dithered = Image.open(dithered_path).convert('RGB')

    if img_banded.size != img_dithered.size:
        print("Resizing images to match...")
        img_dithered = img_dithered.resize(img_banded.size)

    width, height = img_banded.size
    frames = []
    
    # Total 17 frames (0 to 16)
    total_frames = 17
    
    # We want a wipe effect.
    # Frame 0: Split at Width (All Left/Banded).
    # Frame 16: Split at 0 (All Right/Dithered).
    
    # Create forward frames (0 to 16)
    # Ping-pong: 0 -> ... -> 16 -> ... -> 1 -> (loop to 0)
    
    for i in range(total_frames):
        # Linear interpolation for the split position
        # i=0 -> progress=0 -> split=width
        # i=16 -> progress=1 -> split=0
        progress = i / (total_frames - 1)
        split_x = int(width * (1 - progress))
        
        # Create a new frame
        frame = Image.new('RGB', (width, height))
        
        # Paste Banded on the left (0 to split_x)
        if split_x > 0:
            region_banded = img_banded.crop((0, 0, split_x, height))
            frame.paste(region_banded, (0, 0))
            
        # Paste Dithered on the right (split_x to width)
        if split_x < width:
            region_dithered = img_dithered.crop((split_x, 0, width, height))
            frame.paste(region_dithered, (split_x, 0))
            
        frames.append(frame)

    
    # Sequence: 0, 1, ..., 15, 16, 15, ..., 1. (Loop back to 0)
    final_frames = frames + frames[-2:0:-1]
    
    frame_duration_ms = int(1000 / 48) # ~20ms
    
    num_transition_frames = total_frames - 2 # 15 frames (1 to 15)
    transition_time = num_transition_frames * frame_duration_ms # 15 * 20 = 300ms
    
    # Total time for one sweep = 1500ms
    # Hold time = 1500 - 300 = 1200ms
    hold_duration = 1500 - transition_time
    
    # Initialize all with default duration
    durations = [frame_duration_ms] * len(final_frames)
    
    # Set hold times
    # Index 0 is Frame 0
    durations[0] = hold_duration
    
    # Index 16 is Frame 16
    durations[16] = hold_duration
    
    # Rotate left by 8
    shift_amount = 8
    final_frames = final_frames[shift_amount:] + final_frames[:shift_amount]
    durations = durations[shift_amount:] + durations[:shift_amount]

    print(f"Saving animated GIF to {output_path} with {len(final_frames)} frames (Ping-Pong, Shifted {shift_amount})...")
    final_frames[0].save(
        output_path,
        format='GIF',
        save_all=True,
        append_images=final_frames[1:],
        duration=durations,
        loop=0,
        optimize=True
    )
    
    file_size = os.path.getsize(output_path)
    print(f"Done. File size: {file_size} bytes")

if __name__ == "__main__":
    create_animation()
